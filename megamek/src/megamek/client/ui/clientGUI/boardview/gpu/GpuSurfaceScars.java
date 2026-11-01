/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.ShortArray;
import megamek.common.board.Coords;

/**
 * Sparse surface tiles on the real receiving mesh. Images accumulate; geometry does not accumulate per shot. A tile's
 * patch draws with its receiver's own program (GpuTerrain): its vertices add the page UV and image layer, and a
 * per-draw uniform, u_surfaceScar (scar-material.glsl), tells a patch's draw from the receiver's.
 */
final class GpuSurfaceScars implements Disposable {
    /** A patch vertex's page UV and image layer, after its receiver's own attributes. */
    private static final String UV = "a_scarUV";
    /** Where the terrain's model fragment stage takes the scar helpers. */
    private static final String MODEL_SURFACE =
          "vec3 modelSurface(vec3 albedo, vec3 emission, vec3 normal, vec3 geometric, vec3 view) {";
    /** Where it applies the scar material: before the shading frame's repair. */
    private static final String SHADING_NORMAL = "normal = shadingNormal(normal, geometric, view);";
    /** The shading frame's derivatives precede a patch's coverage test. */
    private static final String FRAME_READY = "// surface-frame-ready";
    static final class Marking extends Attribute {
        static final long TYPE = register("boardSurfaceScar");
        Marking() { super(TYPE); }
        @Override public Attribute copy() { return new Marking(); }
        @Override public int compareTo(Attribute other) { return Long.compare(type, other.type); }
    }

    /** Logical receiver survives replacement of its chunk's GPU buffers. */
    record Receiver(Coords coords, int range, int prop, int part) {
        Receiver owner() { return new Receiver(coords, -1, prop, -1); }
    }
    record Source(Receiver receiver, Renderable view) { }
    private record Key(Receiver receiver, int axis, int x, int y, int z) { }
    private static final int MAX_PENDING = 64;
    private final GpuGroundDamage damage;
    private final ArrayDeque<GpuGroundDamage.Impact> pending = new ArrayDeque<>();
    private final Map<Receiver, Geometry> geometry = new HashMap<>();
    private final Map<Key, Page> tiles = new HashMap<>();
    /** A recoloured object's patch keeps its placement's colours (userData), which a merged page cannot carry. */
    private final GpuTerrainPages batches = new GpuTerrainPages(part -> part.userData == null, true);
    private int boardId = Integer.MIN_VALUE;
    private long geometryRevision = Long.MIN_VALUE;

    GpuSurfaceScars(GpuGroundDamage damage) { this.damage = damage; }

    static VertexAttributes attributes(VertexAttributes source) {
        List<VertexAttribute> attributes = new ArrayList<>();
        source.forEach(attribute -> attributes.add(attribute.copy()));
        attributes.add(new VertexAttribute(VertexAttributes.Usage.Generic, 3, UV, 9));
        return new VertexAttributes(attributes.toArray(VertexAttribute[]::new));
    }

    /**
     * The receiver's layout key (getMaskWithSizePacked) of a mesh's attributes: a patch's without its page UV, and a
     * charted wall's without its chart (GpuMaterialCache.CHART), which only the cache's own programs read (they tell
     * walls apart themselves).
     */
    static long receiverLayout(VertexAttributes attributes) {
        long mask = 0;
        int count = 0;
        for (int i = 0, end = attributes.size() - (patch(attributes) ? 1 : 0); i < end; i++) {
            if (attributes.get(i).alias.equals(GpuMaterialCache.CHART)) { continue; }
            mask |= attributes.get(i).usage;
            count++;
        }
        return mask | (long) count << 32;
    }

    /** Whether a layout is a patch's: its receiver's, then the page UV. */
    private static boolean patch(VertexAttributes attributes) {
        return attributes.size() > 0 && UV.equals(attributes.get(attributes.size() - 1).alias);
    }

    /**
     * The layout a program created for {@code mesh} maps its vertex attributes from, when the program reads the page UV
     * and draws without instances: the mesh's own plus the page UV, so the program binds its patches' page UV too. Null
     * otherwise. A client-side layout without GL buffers; the caller disposes it.
     */
    static Mesh layout(ShaderProgram program, Mesh mesh) {
        var attributes = mesh.getVertexAttributes();
        if (mesh.isInstanced() || program.getAttributeLocation(UV) < 0 || patch(attributes)) { return null; }
        return new Mesh(Mesh.VertexDataType.VertexArray, true, 0, 0, attributes(attributes));
    }

    static Material material(Material source) {
        var material = new Material(source);
        material.set(new Marking());
        // A family program never draws a scar: the patch draws with the generic program of its receiver's material.
        material.remove(GpuFamilyPrograms.Constants.TYPE);
        return material;
    }

    /** Every terrain vertex stage forwards the page UV; a receiver's mesh has none, and its own draws never read it. */
    static String vertex(String source) {
        return GpuShaderSource.replaceOnce(source, "void main() {",
              "in vec3 " + UV + ";\nout vec3 v_scarUV;\nvoid main() {\nv_scarUV = " + UV + ";\n", "scar vertex");
    }

    /**
     * The terrain's model fragment stage (model.frag) with the scar helpers (scar-material.glsl): a model draws its own
     * patches, which shade only where their image covers it. Unit programs keep model.frag without them.
     */
    static String fragment(String source) {
        String helpers = "#define GROUND_DAMAGE_FRAGMENT\n" + GpuGroundDamage.shaderSource()
              + GpuShaderSource.read("scar-material.glsl");
        source = GpuShaderSource.replaceOnce(source, MODEL_SURFACE, helpers + "\n" + MODEL_SURFACE, "scar model");
        source = GpuShaderSource.replaceOnce(source, SHADING_NORMAL, "float scarCavity = 1.0;\n"
              + "scarMaterial(surfaceScarMask, modelVertexNormal(geometric), albedo, 1.0, albedo, normal, scarCavity,"
              + " roughness);\n"
              // Churned soil and char keep none of the receiver's metal, scattering or glow.
              + "metallic *= 1.0 - surfaceScarMask.r; scatter *= 1.0 - surfaceScarMask.r; emission *= 1.0 - surfaceScarMask.r;\n"
              + SHADING_NORMAL, "scar model");
        // The helpers exist in lit programs only (model-surface.glsl), the only ones a patch draws with.
        return GpuShaderSource.replaceOnce(source, FRAME_READY, FRAME_READY
              + "\n#ifdef lightingFlag\nif (!surfaceScarCovers()) { fragColor = vec4(0.0); return; }\n#endif\n",
              "scar model");
    }

    /** The per-draw gate: 1 for a patch's draw, 0 for its receiver's own. */
    static void register(DefaultShader shader) {
        shader.register("u_surfaceScar", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                target.set(id, attributes.has(Marking.TYPE) ? 1f : 0f);
            }
        });
    }
    int tileCount() { return tiles.size(); }
    int triangleCount() { return tiles.values().stream().mapToInt(page -> page.triangles).sum(); }

    void update(BoardScene scene) {
        if (scene == null || scene.boardId() != boardId || damage.tileCount() == 0 && !tiles.isEmpty()) {
            dispose();
            boardId = scene == null ? Integer.MIN_VALUE : scene.boardId();
        }
    }

    void add(GpuGroundDamage.Impact impact) {
        if (pending.size() == MAX_PENDING) { pending.removeFirst(); }
        pending.addLast(impact);
    }

    /** CPU projection is limited to new impacts. Camera movement only selects already-uploaded ranges. */
    void prepare(BiFunction<Vector3, Float, List<Source>> sources, Function<Receiver, List<Source>> resolve, long revision) {
        if (geometryRevision != revision) {
            var owners = new HashSet<Receiver>();
            tiles.keySet().forEach(key -> owners.add(key.receiver()));
            Map<Receiver, Geometry> next = new HashMap<>();
            var changed = new HashSet<Receiver>();
            for (Receiver owner : owners) {
                for (Source source : resolve.apply(owner)) {
                    Geometry old = geometry.get(source.receiver());
                    if (old == null || !old.same(source)) {
                        old = new Geometry(source);
                        changed.add(owner);
                    }
                    next.put(source.receiver(), old);
                }
            }
            geometry.keySet().stream().filter(key -> !next.containsKey(key)).forEach(key -> changed.add(key.owner()));
            geometry.clear(); geometry.putAll(next);
            for (Page page : tiles.values()) { if (changed.contains(page.key.receiver())) { page.build(); } }
        }
        geometryRevision = revision;
        for (int i = 0; i < 2 && !pending.isEmpty(); i++) { paint(pending.removeFirst(), sources); }
    }

    private void paint(GpuGroundDamage.Impact impact, BiFunction<Vector3, Float, List<Source>> sources) {
        var candidates = sources.apply(impact.point(), impact.radius());
        var contact = impact.attack().landscapeContact(impact.point());
        Vector3 normal = contact != null && contact.surface().face() != null ? normal(contact.surface().face()) : null;
        List<Geometry> receivers = new ArrayList<>();
        float nearest = Float.POSITIVE_INFINITY;
        for (Source source : candidates) {
            Geometry mesh = geometry.get(source.receiver());
            if (mesh == null || !mesh.same(source)) { mesh = new Geometry(source); }
            receivers.add(mesh);
            if (contact == null || contact.surface().face() == null) {
                for (int i = 0; i < mesh.indices.length; i += 3) {
                    var a = mesh.point(i); var b = mesh.point(i + 1); var c = mesh.point(i + 2);
                    var candidate = b.cpy().sub(a).crs(c.cpy().sub(a)).nor();
                    float distance = Math.abs(candidate.dot(impact.point().cpy().sub(a)));
                    if (distance < nearest && com.badlogic.gdx.math.Intersector.isPointInTriangle(impact.point(), a, b, c)) {
                        nearest = distance; normal = candidate;
                    }
                }
            }
        }
        if (normal == null || normal.isZero()) { return; }
        var style = impact.style(normal);
        float radius = impact.radius(style);
        var brush = damage.brush(style, radius, impact.strength(), impact.key().hashCode());
        if (brush == null) { return; }
        Vector3 along = impact.incoming().mulAdd(normal, -impact.incoming().dot(normal));
        if (along.len2() < .000001f) { along = new Vector3(0, 0, 1).crs(normal); }
        if (along.len2() < .000001f) { along.set(1, 0, 0); }
        along.nor();
        Vector3 across = normal.cpy().crs(along).nor();
        float size = damage.tileWorldSize();
        Map<Page, BitSet> painted = new HashMap<>();
        var rebuild = new HashSet<Page>();
        var a = new Vector3(); var b = new Vector3(); var c = new Vector3(); var facing = new Vector3();
        var bounds = new BoundingBox();
        for (Geometry mesh : receivers) {
            for (int i = 0; i < mesh.indices.length; i += 3) {
                mesh.point(i, a); mesh.point(i + 1, b); mesh.point(i + 2, c);
                bounds.inf().ext(a).ext(b).ext(c);
                var center = impact.point();
                if (bounds.min.x > center.x + radius || bounds.max.x < center.x - radius
                      || bounds.min.y > center.y + radius || bounds.max.y < center.y - radius
                      || bounds.min.z > center.z + radius || bounds.max.z < center.z - radius) { continue; }
                facing.set(b).sub(a).crs(c.x - a.x, c.y - a.y, c.z - a.z).nor();
                if (facing.dot(normal) < .15f) { continue; }
                int axis = axis(facing);
                for (int x = cell(Math.max(bounds.min.x, center.x - radius), size); x <= cell(Math.min(bounds.max.x, center.x + radius), size); x++) {
                    for (int y = cell(Math.max(bounds.min.y, center.y - radius), size); y <= cell(Math.min(bounds.max.y, center.y + radius), size); y++) {
                        for (int z = cell(Math.max(bounds.min.z, center.z - radius), size); z <= cell(Math.min(bounds.max.z, center.z + radius), size); z++) {
                            var owner = mesh.source.receiver();
                            // Mesh/material ranges are implementation details, not separate paint surfaces.
                            Key key = new Key(owner.owner(), axis, x, y, z);
                            Page page = tiles.get(key);
                            if (page == null) { page = new Page(key); }
                            BitSet written = painted.computeIfAbsent(page, ignored -> new BitSet());
                            raster(page, a, b, c, impact.point(), along, across, normal, radius, brush, written);
                            if (page.image != null) {
                                if (tiles.put(key, page) == null || geometry.get(mesh.source.receiver()) != mesh) { rebuild.add(page); }
                                geometry.put(mesh.source.receiver(), mesh);
                            }
                        }
                    }
                }
            }
        }
        rebuild.forEach(Page::build);
        painted.forEach((page, written) -> { if (!written.isEmpty()) { damage.changed(page.image); } });
    }

    private void raster(Page page, Vector3 a, Vector3 b, Vector3 c, Vector3 center, Vector3 along, Vector3 across,
          Vector3 normal, float radius, GpuGroundDamage.Brush brush, BitSet written) {
        int axis = page.key.axis() % 3;
        float size = damage.tileWorldSize(), density = GpuGroundDamage.TILE_INTERIOR / size;
        Vector3 base = new Vector3(page.key.x() * size, page.key.y() * size, page.key.z() * size);
        float au = u(a, axis), av = v(a, axis), bu = u(b, axis), bv = v(b, axis), cu = u(c, axis), cv = v(c, axis);
        float determinant = (bv - cv) * (au - cu) + (cu - bu) * (av - cv);
        if (Math.abs(determinant) < .000001f) { return; }
        int border = GpuGroundDamage.TILE_BORDER, maximum = GpuGroundDamage.TILE_SIZE - 1;
        // The neighbouring projection needs valid filter samples beyond the shared triangle edge too.
        float padding = 2 / density / Math.abs(determinant);
        float padA = padding * (float) Math.hypot(bu - cu, bv - cv);
        float padB = padding * (float) Math.hypot(cu - au, cv - av);
        float padC = padding * (float) Math.hypot(au - bu, av - bv);
        int x0 = Math.max(0, (int) Math.floor((Math.min(au, Math.min(bu, cu)) - u(base, axis)) * density) + border - 2);
        int x1 = Math.min(maximum, (int) Math.ceil((Math.max(au, Math.max(bu, cu)) - u(base, axis)) * density) + border + 2);
        int y0 = Math.max(0, (int) Math.floor((Math.min(av, Math.min(bv, cv)) - v(base, axis)) * density) + border - 2);
        int y1 = Math.min(maximum, (int) Math.ceil((Math.max(av, Math.max(bv, cv)) - v(base, axis)) * density) + border + 2);
        Vector3 world = new Vector3(), delta = new Vector3();
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
            int index = y * GpuGroundDamage.TILE_SIZE + x;
            if (written.get(index)) { continue; }
            float pu = u(base, axis) + (x - border + .5f) / density, pv = v(base, axis) + (y - border + .5f) / density;
            float wa = ((bv - cv) * (pu - cu) + (cu - bu) * (pv - cv)) / determinant;
            float wb = ((cv - av) * (pu - cu) + (au - cu) * (pv - cv)) / determinant, wc = 1 - wa - wb;
            if (wa < -padA || wb < -padB || wc < -padC) { continue; }
            world.set(a).scl(wa).mulAdd(b, wb).mulAdd(c, wc);
            float depth = component(world, axis) - component(base, axis);
            // The normal-axis cell also needs a filtering border where a deformed wall crosses it.
            if (depth < -border / density || depth > size + border / density) { continue; }
            delta.set(world).sub(center);
            if (Math.abs(delta.dot(normal)) > radius) { continue; }
            int sample = brush.sample(delta.dot(along) / radius, delta.dot(across) / (radius * brush.aspect()));
            if ((sample >>> 24) * brush.strength() < 1) { continue; }
            if (page.image == null) {
                page.image = damage.surfaceTile();
                if (page.image == null) { return; }
            }
            GpuGroundDamage.blend(page.image, x, y, sample, brush.strength());
            written.set(index);
        }
    }

    /**
     * Draws the pages in view whose receiver is {@code shown}: a scar hides with the object the zoom hides. Pages merge
     * until {@code deadline} (GpuMeshPage.advance).
     */
    void render(Camera camera, ModelBatch batch, Environment environment, int boardRows, Predicate<Receiver> shown,
          long deadline) {
        batches.begin(camera);
        for (Page page : tiles.values()) {
            Coords at = page.key.receiver().coords();
            int span = GpuTerrain.CHUNK_SIZE * GpuTerrainPages.CHUNKS_PER_PAGE;
            int group = at.getX() / span * ((boardRows + span - 1) / span) + at.getY() / span;
            batches.add(page.parts, group, camera.frustum.boundsInFrustum(page.bounds) && shown.test(page.key.receiver()));
        }
        batches.render(batch, environment, deadline);
    }

    private final class Page {
        final Key key;
        GpuGroundDamage.Tile image;
        final Array<Renderable> parts = new Array<>();
        final BoundingBox bounds = new BoundingBox();
        final FloatArray points = new FloatArray();
        int triangles;
        Page(Key key) { this.key = key; }

        boolean receives(Receiver receiver) { return key.receiver().coords().equals(receiver.coords()) && key.receiver().prop() == receiver.prop(); }
        void clearMesh() { parts.forEach(part -> part.meshPart.mesh.dispose()); parts.clear(); points.clear(); triangles = 0; }

        void build() {
            clearMesh(); bounds.inf();
            for (Geometry source : geometry.values()) { if (receives(source.source.receiver())) { append(source); } }
        }

        private void append(Geometry source) {
            points.clear();
            int stride = source.stride + 3;
            float size = damage.tileWorldSize();
            var base = new Vector3(key.x() * size, key.y() * size, key.z() * size);
            var a = new Vector3(); var b = new Vector3(); var c = new Vector3(); var normal = new Vector3();
            var triangle = new BoundingBox();
            for (int i = 0; i < source.indices.length; i += 3) {
                source.point(i, a); source.point(i + 1, b); source.point(i + 2, c);
                triangle.inf().ext(a).ext(b).ext(c);
                if (triangle.min.x > base.x + size || triangle.max.x < base.x
                      || triangle.min.y > base.y + size || triangle.max.y < base.y
                      || triangle.min.z > base.z + size || triangle.max.z < base.z) { continue; }
                normal.set(b).sub(a).crs(c.x - a.x, c.y - a.y, c.z - a.z).nor();
                if (axis(normal) != key.axis()) { continue; }
                List<float[]> polygon = new ArrayList<>(List.of(source.vertex(i), source.vertex(i + 1), source.vertex(i + 2)));
                for (int axis = 0; axis < 3 && !polygon.isEmpty(); axis++) {
                    int cell = axis == 0 ? key.x() : axis == 1 ? key.y() : key.z();
                    polygon = clip(polygon, source, axis, cell * size, true);
                    polygon = clip(polygon, source, axis, (cell + 1) * size, false);
                }
                for (int vertex = 1; vertex + 1 < polygon.size(); vertex++) {
                    for (var value : List.of(polygon.getFirst(), polygon.get(vertex), polygon.get(vertex + 1))) {
                        int p = source.position;
                        var world = new Vector3(value[p], value[p + 1], value[p + 2]);
                        bounds.ext(world);
                        float[] copied = value.clone();
                        // Tiny normal offset keeps the cached patch ahead of its identical opaque support.
                        for (int k = 0; k < 3; k++) { copied[p + k] += component(normal, k) * BoardRelief.metres(.005f); }
                        points.addAll(copied);
                        points.add((u(world, key.axis() % 3) - u(base, key.axis() % 3)) / size);
                        points.add((v(world, key.axis() % 3) - v(base, key.axis() % 3)) / size);
                        points.add(image.layer());
                    }
                }
            }
            if (points.isEmpty()) { return; }
            triangles += points.size / stride / 3;
            // Small tile meshes normally fit one range; split pathological authored meshes without wrapping indices.
            for (int first = 0; first < points.size / stride; first += 65535) {
                int count = Math.min(65535, points.size / stride - first);
                var mesh = new Mesh(true, count, count, attributes(source.attributes));
                mesh.setVertices(points.items, first * stride, count * stride);
                short[] indices = new short[count];
                for (int j = 0; j < count; j++) { indices[j] = (short) j; }
                mesh.setIndices(indices);
                var part = new Renderable();
                part.material = material(source.source.view().material);
                // The copied vertices of a recolourable model's shared mesh take its placement's colours (RigidGlb.packed).
                part.userData = source.source.view().userData;
                part.meshPart.set("surface-scar", mesh, 0, count, GL20.GL_TRIANGLES);
                parts.add(part);
            }
        }
    }

    private static final class Geometry {
        final Source source;
        final VertexAttributes attributes;
        final float[] vertices;
        final short[] indices;
        final int stride, position, colour;
        Geometry(Source source) {
            var snapshot = new Renderable().set(source.view());
            snapshot.material = new Material(snapshot.material);
            this.source = new Source(source.receiver(), snapshot);
            var part = source.view();
            attributes = part.meshPart.mesh.getVertexAttributes();
            stride = attributes.vertexSize / Float.BYTES;
            position = attributes.findByUsage(VertexAttributes.Usage.Position).offset / Float.BYTES;
            var color = attributes.findByUsage(VertexAttributes.Usage.ColorPacked);
            colour = color == null ? -1 : color.offset / Float.BYTES;
            var points = new FloatArray(); var elements = new ShortArray();
            GpuPropBatch.copyVertices(part.meshPart.mesh, part.meshPart.offset, part.meshPart.size, points, elements);
            vertices = points.toArray(); indices = elements.toArray();
            var normal = attributes.findByUsage(VertexAttributes.Usage.Normal);
            Matrix4 normals = part.worldTransform.cpy().inv().tra();
            boolean transformed = !Arrays.equals(part.worldTransform.val, new Matrix4().val);
            for (int i = 0; i < vertices.length; i += stride) {
                var world = new Vector3(vertices[i + position], vertices[i + position + 1], vertices[i + position + 2]).mul(part.worldTransform);
                vertices[i + position] = world.x; vertices[i + position + 1] = world.y; vertices[i + position + 2] = world.z;
                if (normal != null && transformed) {
                    int n = i + normal.offset / Float.BYTES;
                    world.set(vertices[n], vertices[n + 1], vertices[n + 2]).rot(normals).nor();
                    vertices[n] = world.x; vertices[n + 1] = world.y; vertices[n + 2] = world.z;
                }
            }
        }
        boolean same(Source other) {
            var a = source.view(); var b = other.view();
            return a.meshPart.mesh == b.meshPart.mesh && a.meshPart.offset == b.meshPart.offset && a.meshPart.size == b.meshPart.size
                  && a.material.equals(b.material) && a.worldTransform.equals(b.worldTransform)
                  // A recolour keeps the shared slotted mesh: only the colours in userData tell it.
                  && Arrays.equals(RigidGlb.slotColours(a.userData), RigidGlb.slotColours(b.userData));
        }
        float[] vertex(int index) {
            int at = Short.toUnsignedInt(indices[index]) * stride;
            return Arrays.copyOfRange(vertices, at, at + stride);
        }
        Vector3 point(int index) { return point(index, new Vector3()); }
        Vector3 point(int index, Vector3 result) {
            int at = Short.toUnsignedInt(indices[index]) * stride + position;
            return result.set(vertices[at], vertices[at + 1], vertices[at + 2]);
        }
    }

    private static List<float[]> clip(List<float[]> polygon, Geometry source, int axis, float plane, boolean above) {
        List<float[]> result = new ArrayList<>();
        if (polygon.isEmpty()) { return result; }
        float[] previous = polygon.getLast();
        float d0 = (previous[source.position + axis] - plane) * (above ? 1 : -1);
        for (float[] current : polygon) {
            float d1 = (current[source.position + axis] - plane) * (above ? 1 : -1);
            if ((d0 >= 0) != (d1 >= 0)) {
                float t = d0 / (d0 - d1);
                float[] middle = new float[source.stride];
                for (int i = 0; i < middle.length; i++) {
                    if (i == source.colour) {
                        int a = Float.floatToRawIntBits(previous[i]), b = Float.floatToRawIntBits(current[i]), colour = 0;
                        for (int shift = 0; shift < 32; shift += 8) {
                            colour |= Math.round((a >>> shift & 255) * (1 - t) + (b >>> shift & 255) * t) << shift;
                        }
                        middle[i] = Float.intBitsToFloat(colour & 0xfeffffff);
                    } else { middle[i] = previous[i] + (current[i] - previous[i]) * t; }
                }
                result.add(middle);
            }
            if (d1 >= 0) { result.add(current); }
            previous = current; d0 = d1;
        }
        return result;
    }

    private static Vector3 normal(BoardSurface.Face face) { return face.b().cpy().sub(face.a()).crs(face.c().cpy().sub(face.a())).nor(); }
    private static int axis(Vector3 normal) {
        int axis = Math.abs(normal.x) >= Math.abs(normal.y) && Math.abs(normal.x) >= Math.abs(normal.z) ? 0
              : Math.abs(normal.y) >= Math.abs(normal.z) ? 1 : 2;
        return axis + (component(normal, axis) < 0 ? 3 : 0);
    }
    private static int cell(float coordinate, float size) { return (int) Math.floor(coordinate / size); }
    private static float component(Vector3 point, int axis) { return axis == 0 ? point.x : axis == 1 ? point.y : point.z; }
    private static float u(Vector3 point, int axis) { return axis == 0 ? point.y : point.x; }
    private static float v(Vector3 point, int axis) { return axis == 2 ? point.y : point.z; }

    @Override public void dispose() {
        batches.dispose(); tiles.values().forEach(Page::clearMesh); tiles.clear(); geometry.clear(); pending.clear();
        geometryRevision = Long.MIN_VALUE;
    }
}
