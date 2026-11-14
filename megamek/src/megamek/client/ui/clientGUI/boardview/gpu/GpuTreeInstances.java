/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.Shader;
import com.badlogic.gdx.graphics.g3d.model.MeshPart;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.model.NodePart;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DepthShader;
import com.badlogic.gdx.graphics.g3d.utils.DepthShaderProvider;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.ShortArray;

/**
 * The board's instancing: the instance format every instanced program reads ({@link #STRIDE} floats a placement,
 * tree-instances.glsl), the instanced vertex stages and depth providers, and the tree stands ({@link Stand}): a chunk's
 * trees by species, each at its own detail level, dissolving between levels, which GpuIndirectInstances draws as lists
 * ({@link #list}). Each model at each level is held once in its pools; a placement adds only its place, turn, size and
 * mirror. Render thread.
 */
final class GpuTreeInstances {
    /** Marks the materials of instanced renderables, so the shader providers pick the instanced shaders. */
    static final class Instanced extends Attribute {
        static final long TYPE = register("boardInstanced");
        /**
         * Its program discards the pixels of a level that another one keeps while its instances dissolve between them
         * (level-dissolve.glsl): a kit structure's parts in their dissolve's lists (GpuTerrain.Chunk#shareProps). A
         * value, not an attribute type of its own (libGDX's 64 types are all taken): the terrain's programs tell the two
         * apart (GpuTerrain.TerrainShaders). A tree's foliage program always dissolves.
         */
        final boolean dissolve;

        Instanced() { this(false); }

        Instanced(boolean dissolve) {
            super(TYPE);
            this.dissolve = dissolve;
        }

        @Override
        public Attribute copy() { return new Instanced(dissolve); }

        @Override
        public int compareTo(Attribute other) {
            return type != other.type ? Long.compare(type, other.type) : Boolean.compare(dissolve, ((Instanced) other).dissolve);
        }

        @Override
        public int hashCode() { return 31 * super.hashCode() + (dissolve ? 1 : 0); }
    }

    /**
     * Floats per tree: x, y, z and the horizontal scale, negative for a mirror image (its model's y axis reversed before
     * the turn, tree-instances.glsl); the cosine and sine of its turn, its vertical scale, and its dissolve: 0 at rest,
     * else when a tree began dissolving from one detail level into another, positive at the level it takes and negative
     * at the one it leaves ({@link Stand#detail}).
     */
    static final int STRIDE = 8;
    private static final int DISSOLVE = 7;
    private static final String MAIN = "void main() {";

    /**
     * libGDX tells a mesh's attributes apart by usage and unit, and adds the unit to the shader location, so the
     * instance attributes differ by usage: the place and horizontal scale, the turn and vertical scale, and for a
     * recolourable model's shared mesh ({@code colours}) its placement's slot colours (float, not normalised).
     */
    private static VertexAttribute[] attributes(boolean colours) {
        VertexAttribute place = new VertexAttribute(VertexAttributes.Usage.Position, 4, "a_instance0");
        VertexAttribute turn = new VertexAttribute(VertexAttributes.Usage.Generic, 4, "a_instance1");
        return colours ? new VertexAttribute[] { place, turn, new VertexAttribute(VertexAttributes.Usage.ColorUnpacked, 4, "a_instance2") }
              : new VertexAttribute[] { place, turn };
    }

    /** A part's material in an instanced group: its own copy, marked so the shader providers pick the instanced shaders. */
    static Material instancedMaterial(Material material) {
        Material result = new Material(material);
        result.set(new Instanced());
        return result;
    }

    /**
     * An instanced material's copy whose program dissolves between levels ({@link Instanced#dissolve}), or, with
     * {@code dissolve} false, the one it draws with at rest.
     */
    static Material dissolving(Material instanced, boolean dissolve) {
        Material result = new Material(instanced);
        result.set(new Instanced(dissolve));
        return result;
    }

    /** Whether the material's program dissolves between levels ({@link #dissolving}). */
    static boolean dissolves(Attributes material) {
        var instanced = material.get(Instanced.class, Instanced.TYPE);
        return instanced != null && instanced.dissolve;
    }

    /** Each node part of these nodes and their children, in the order a stand's list draws them. */
    private static void forEachPart(Iterable<Node> nodes, Consumer<NodePart> action) {
        for (Node node : nodes) {
            for (NodePart part : node.parts) { action.accept(part); }
            forEachPart(node.getChildren(), action);
        }
    }

    /**
     * The records of a GPU-driven tree list's runs (GpuIndirectInstances#trees): what moves the trees, as a stand's
     * mode names it (GpuInstancedMesh#STANDING, their sway in a blast; #FELLED, their fall).
     */
    private static final GpuIndirectInstances.Pieces STANDING = moved(GpuInstancedMesh.STANDING),
          FELLED = moved(GpuInstancedMesh.FELLED);

    private static GpuIndirectInstances.Pieces moved(float mode) {
        return new GpuIndirectInstances.Pieces() {
            @Override
            public float[] record(Renderable sample) {
                float[] record = new float[GpuIndirectInstances.RECORD_FLOATS];
                record[3] = mode;
                return record;
            }

            @Override
            public void released(Renderable sample, float[] record) { }
        };
    }

    /** Each species' parts at each level, then those that fade, for the GPU-driven path's lists ({@link #runs}). */
    private final Map<String, Renderable[][]> standParts = new HashMap<>();

    /**
     * A stand as a GPU-driven snapshot (GpuIndirectInstances#trees), in place of {@code current}: each tree at the level
     * it takes, its dissolve riding in its instance ({@code level} -1: the colour and depth passes'), or all of them at
     * {@code level} (the shadow map's); with {@code fades} only the parts that fade (the fade pass's). Written again when the
     * stand's trees, or its levels' runs, changed; its runs' records say what moves its trees (standing or felled).
     */
    GpuIndirectInstances.Instances list(GpuIndirectInstances indirect, GpuIndirectInstances.Instances current, Stand stand,
          int level, boolean fades) {
        // The runs, or the trees and the level: what a list of each kind was written for. A species not split since its
        // trees changed splits first, which counts among the runs: the revision read after it is the one written.
        if (level < 0) { stand.trees.keySet().forEach(stand::levels); }
        int revision = level < 0 ? stand.runs : stand.contents * TreeLod.LEVELS + level;
        if (GpuIndirectInstances.holds(current, stand, revision)) { return current; }
        List<Renderable[]> parts = new ArrayList<>();
        List<FloatArray> runs = new ArrayList<>();
        runs(stand, level, fades, parts, runs);
        return indirect.trees(current, stand, revision, parts, runs, felled ? FELLED : STANDING);
    }

    /**
     * A stand's runs (#list): each species' trees at each level with trees, and the parts of that level's model that
     * draw them, or with {@code fades} those that fade.
     */
    private void runs(Stand stand, int level, boolean fades, List<Renderable[]> parts, List<FloatArray> runs) {
        for (String name : stand.trees.keySet()) {
            for (int lod = 0; lod < TreeLod.LEVELS; lod++) {
                FloatArray run = level < 0 ? stand.levels(name)[lod] : lod == level ? stand.trees.get(name) : null;
                if (run == null || run.size == 0) { continue; }
                Renderable[] drawn = standParts(name, lod, fades);
                if (drawn.length == 0) { continue; }
                parts.add(drawn);
                runs.add(run);
            }
        }
    }

    private Renderable[] standParts(String name, int lod, boolean fades) {
        Renderable[][] known = standParts.computeIfAbsent(name, key -> new Renderable[TreeLod.LEVELS * 2][]);
        int at = lod * 2 + (fades ? 1 : 0);
        if (known[at] == null) {
            List<Renderable> result = new ArrayList<>();
            forEachPart(models.apply(name, lod).nodes, part -> {
                if (fades && !GpuCutoutFade.fades(part.material)) { return; }
                Renderable renderable = new Renderable();
                renderable.meshPart.set(part.meshPart);
                renderable.material = fades ? GpuCutoutFade.fadeMaterial(part.material) : part.material;
                result.add(renderable);
            });
            known[at] = result.toArray(Renderable[]::new);
        }
        return known[at];
    }

    /** The meshes of the stands' parts listed so far (#list): the pools keep their geometry (GpuIndirectInstances#retain). */
    void meshes(java.util.Set<com.badlogic.gdx.graphics.Mesh> out) {
        for (Renderable[][] levels : standParts.values()) {
            for (Renderable[] parts : levels) {
                if (parts == null) { continue; }
                for (Renderable part : parts) { out.add(part.meshPart.mesh); }
            }
        }
    }

    /**
     * The parts a stand of this species draws at every detail level, each as its own material and vertex layout, for
     * programs built before the stand first draws (GpuTerrain's warm-up, which picks the instanced program a part's list
     * draws with).
     */
    void speciesParts(String species, BiConsumer<Material, VertexAttributes> action) {
        for (int level = 0; level < TreeLod.LEVELS; level++) {
            Model model = models.apply(species, level);
            VertexAttributes layout = model.meshes.first().getVertexAttributes();
            forEachPart(model.nodes, part -> action.accept(part.material, layout));
        }
    }

    /** A part's instance layout, including colour slots when its vertex layout addresses them. */
    static VertexAttributes instanceAttributes(VertexAttributes layout) {
        return new VertexAttributes(attributes(RigidGlb.colourSlotted(layout)));
    }

    /**
     * One chunk's trees, grouped by species; each tree as {@link #STRIDE} floats, largest first, so the trees that take
     * one detail level at a scale ({@link #detail}) lie in one run of them.
     */
    static final class Stand {
        final Map<String, FloatArray> trees = new LinkedHashMap<>();
        /** Each species' trees' bounding diagonals, in world units, in their order. */
        private final Map<String, FloatArray> diameters = new HashMap<>();
        /** Each species' first tree at each detail level, then its count ({@link TreeLod#LEVELS} + 1 entries). */
        private final Map<String, int[]> starts = new HashMap<>();
        /** Each species' trees at each detail level: copies of its runs, new only where a run changed. */
        private final Map<String, FloatArray[]> levels = new HashMap<>();
        /** The species with trees dissolving between levels. */
        private final Map<String, Dissolve> dissolves = new HashMap<>();
        private float pixelsPerUnit, now, duration, settles;
        /**
         * For tools that compare detail levels in the board's own frame (GpuImpostorCalibrationSmokeTest): every tree
         * at this level, none at {@link TreeLod#LEVELS}; -1, the game's, lets each choose. The shadow map keeps the level
         * its texel chooses (GpuTerrain#renderDepth), as it does when a tree changes level in the game.
         */
        static int forced = -1;
        /** The forced level the last split took, so that a change, or its end, splits again. */
        private int splitForced = -1;
        /** Each tree's level in a split, reused. */
        private int[] own = new int[0];
        /**
         * Counts changes of its trees (added, cleared) and, in {@code runs}, of its levels' runs too: what the GPU-driven
         * path's lists of it were written for (GpuTerrain#standList).
         */
        int contents, runs;

        /** A species' trees between two levels: the level each leaves (-1: none) and when it began to (clock seconds). */
        private static final class Dissolve {
            final int[] leaving;
            final float[] began;

            Dissolve(int trees) {
                leaving = new int[trees];
                Arrays.fill(leaving, -1);
                began = new float[trees];
            }
        }

        /**
         * A tree's transform is a translation, a turn about z and a scale that is equal along x and y; {@code diameter}
         * is its bounds' diagonal in world units, against which its detail level is chosen.
         */
        void add(String species, Matrix4 transform, float diameter) { add(species, transform, diameter, 0); }

        /** A felled tree ({@link GpuDestruction}), whose instance names its hex's slot + 1 ({@code owner}). */
        void add(String species, Matrix4 transform, float diameter, int owner) {
            FloatArray sizes = diameters.computeIfAbsent(species, key -> new FloatArray());
            int at = sizes.size;
            while (at > 0 && sizes.get(at - 1) < diameter) { at--; }
            sizes.insert(at, diameter);
            FloatArray tree = new FloatArray(STRIDE);
            append(tree, transform, owner);
            FloatArray placed = trees.computeIfAbsent(species, key -> new FloatArray());
            placed.insertRange(at * STRIDE, STRIDE);
            System.arraycopy(tree.items, 0, placed.items, at * STRIDE, STRIDE);
            levels.remove(species);
            contents++;
            runs++;
        }

        /** No trees; the scale stays for the next ones (GpuTerrain.Chunk#restand). */
        void clear() {
            trees.clear();
            diameters.clear();
            starts.clear();
            levels.clear();
            dissolves.clear();
            contents++;
            runs++;
        }

        /**
         * Each tree at the level its own diameter asks at this scale ({@link TreeLod#level}), past each threshold's
         * hysteresis from its level at the last scale. A larger tree never takes a coarser level than a smaller one.
         * A tree that changes level dissolves from one into the other over {@code duration} seconds of the clock
         * ({@code now}, positive): it draws at both, which keep complementary pixels (terrain-foliage.frag), and holds
         * the level it takes until the dissolve ends. Returns when the last dissolve ends, 0 if none: a call from then
         * on drops the levels they left. With {@code duration} 0 trees switch at once, and dissolves end.
         */
        float detail(float pixelsPerUnit, float now, float duration) {
            // The same scale with every species split and nothing dissolving changes nothing: a still perspective view
            // asks every frame.
            boolean unchanged = forced == splitForced && pixelsPerUnit == this.pixelsPerUnit && dissolves.isEmpty()
                  && levels.size() == trees.size();
            splitForced = forced;
            this.pixelsPerUnit = pixelsPerUnit;
            this.now = now;
            this.duration = duration;
            settles = 0;
            if (unchanged) { return 0; }
            for (String species : trees.keySet()) { split(species); }
            return settles;
        }

        /** A species' trees at each detail level at the last scale. */
        FloatArray[] levels(String species) {
            FloatArray[] result = levels.get(species);
            return result != null ? result : split(species);
        }

        private FloatArray[] split(String species) {
            if (forced == TreeLod.LEVELS) {
                FloatArray[] none = new FloatArray[TreeLod.LEVELS];
                Arrays.setAll(none, lod -> new FloatArray(1));
                starts.remove(species);
                levels.put(species, none);
                return none;
            }
            FloatArray sizes = diameters.get(species), all = trees.get(species);
            int[] previous = starts.get(species);
            // New or changed trees start from the nearest level, as a new chunk's did, and dissolve from none.
            if (previous != null && previous[TreeLod.LEVELS] != sizes.size) { previous = null; }
            if (previous == null) { dissolves.remove(species); }
            Dissolve dissolve = dissolves.get(species);
            if (own.length < sizes.size) { own = new int[sizes.size]; }
            for (int i = 0, was = 0; i < sizes.size; i++) {
                while (previous != null && was < TreeLod.LEVELS - 1 && i >= previous[was + 1]) { was++; }
                own[i] = forced >= 0 ? forced : holds(dissolve, i) ? was : TreeLod.level(sizes.get(i) * pixelsPerUnit, was);
            }
            // A larger tree never takes a coarser level than a smaller one: the others give way to the trees that hold.
            for (int i = 0, floor = 0; dissolve != null && i < sizes.size; i++) {
                if (holds(dissolve, i)) { floor = own[i]; } else { own[i] = Math.max(own[i], floor); }
            }
            for (int i = sizes.size - 1, ceiling = TreeLod.LEVELS - 1; dissolve != null && i >= 0; i--) {
                if (holds(dissolve, i)) { ceiling = own[i]; } else { own[i] = Math.min(own[i], ceiling); }
            }
            int[] next = new int[TreeLod.LEVELS + 1];
            int level = 0;
            for (int i = 0; i < sizes.size; i++) {
                while (level < own[i]) { next[++level] = i; }
            }
            while (level < TreeLod.LEVELS) { next[++level] = sizes.size; }
            // The levels whose runs a dissolve that begins or ends changes, as bits.
            int changed = 0;
            for (int i = 0, was = 0, is = 0; previous != null && i < sizes.size; i++) {
                while (was < TreeLod.LEVELS - 1 && i >= previous[was + 1]) { was++; }
                while (is < TreeLod.LEVELS - 1 && i >= next[is + 1]) { is++; }
                int leaving = dissolve == null ? -1 : dissolve.leaving[i];
                boolean begins = was != is && duration > 0 && now > 0;
                if (!begins && (leaving < 0 || holds(dissolve, i))) { continue; }
                changed |= 1 << is | (leaving >= 0 ? 1 << leaving : 0);
                if (begins) {
                    if (dissolve == null) { dissolves.put(species, dissolve = new Dissolve(sizes.size)); }
                    dissolve.began[i] = now;
                    dissolve.leaving[i] = was;
                    changed |= 1 << was;
                } else {
                    dissolve.leaving[i] = -1;
                }
            }
            boolean dissolving = false;
            for (int i = 0; dissolve != null && i < sizes.size; i++) {
                if (dissolve.leaving[i] < 0) { continue; }
                dissolving = true;
                settles = Math.max(settles, dissolve.began[i] + duration);
            }
            if (!dissolving) { dissolves.remove(species); }
            FloatArray[] old = levels.get(species), result = new FloatArray[TreeLod.LEVELS];
            for (int lod = 0; lod < TreeLod.LEVELS; lod++) {
                if (old != null && previous != null && (changed & 1 << lod) == 0 && previous[lod] == next[lod]
                      && previous[lod + 1] == next[lod + 1]) {
                    result[lod] = old[lod];
                    continue;
                }
                int count = (next[lod + 1] - next[lod]) * STRIDE;
                FloatArray run = new FloatArray(Math.max(count, 1));
                run.addAll(all, next[lod] * STRIDE, count);
                // A dissolving tree draws at the level it takes from when it began, and at the one it leaves until it ends.
                for (int i = next[lod]; dissolving && i < next[lod + 1]; i++) {
                    if (dissolve.leaving[i] >= 0) { run.items[(i - next[lod]) * STRIDE + DISSOLVE] = dissolve.began[i]; }
                }
                for (int i = 0; dissolving && i < sizes.size; i++) {
                    if (dissolve.leaving[i] != lod) { continue; }
                    run.addAll(all, i * STRIDE, STRIDE);
                    run.items[run.size - STRIDE + DISSOLVE] = -dissolve.began[i];
                }
                result[lod] = run;
            }
            starts.put(species, next);
            levels.put(species, result);
            if (old == null || !Arrays.equals(old, result)) { runs++; }
            return result;
        }

        /** Whether a species' tree is dissolving: it holds its level until the dissolve ends. */
        private boolean holds(Dissolve dissolve, int tree) {
            return dissolve != null && dissolve.leaving[tree] >= 0 && now - dissolve.began[tree] < duration;
        }
    }

    /**
     * A part's indexed range compacted into {@code packed} and {@code indices} (both replaced), its vertices numbered as
     * its indices first reach them: what GpuIndirectInstances places in its pools.
     */
    static void compact(MeshPart part, FloatArray packed, ShortArray indices) {
        GpuPropBatch.copyVertices(part.mesh, part.offset, part.size, packed, indices);
        int stride = part.mesh.getVertexSize() / Float.BYTES;
        // Fragment meshes are already packed in first-use order by KitFragments. Preserve that output directly:
        // copying and renumbering it again made the first frame of a mass collapse pay for every fragment twice.
        int next = 0;
        for (int i = 0; i < indices.size; i++) {
            int vertex = Short.toUnsignedInt(indices.items[i]);
            if (vertex > next) { next = -1; break; }
            if (vertex == next) { next++; }
        }
        if (next == packed.size / stride) { return; }
        FloatArray vertices = new FloatArray(packed);
        int[] remap = new int[vertices.size / stride];
        Arrays.fill(remap, -1);
        packed.clear();
        for (int i = 0; i < indices.size; i++) {
            int original = Short.toUnsignedInt(indices.items[i]);
            if (remap[original] < 0) {
                remap[original] = packed.size / stride;
                packed.addAll(vertices.items, original * stride, stride);
            }
            indices.items[i] = (short) remap[original];
        }
    }

    private final BiFunction<String, Integer, Model> models;
    /** Whether its trees are felled ones (GpuDestruction), whose instances name their slots instead of a dissolve. */
    private final boolean felled;

    /**
     * {@code models}: loads a tree model, already marked for the foliage shader, by asset name and level; with
     * {@code felled} its trees fall (GpuDestruction): their stands never dissolve between levels.
     */
    GpuTreeInstances(BiFunction<String, Integer, Model> models, boolean felled) {
        this.models = models;
        this.felled = felled;
    }

    /**
     * Whether a tree's instance data can hold this transform: a place, a turn about z, one positive scale along x and y
     * and a positive vertical scale. Tilted, mirrored, sheared or unevenly stretched placements cannot be instanced in a
     * stand, whose lists keep the counter-clockwise front face.
     */
    static boolean placeable(Matrix4 transform) {
        return placeable(transform, false);
    }

    /**
     * Whether an object or module part's instance data can hold this transform: a {@link #placeable} one or its mirror
     * image, which GpuIndirectInstances draws in a group of its own with the front face flipped. Tilted, sheared or
     * unevenly stretched placements cannot be instanced.
     */
    static boolean instanceable(Matrix4 transform) {
        return placeable(transform, true);
    }

    private static boolean placeable(Matrix4 transform, boolean mirrors) {
        float[] m = transform.val;
        double x = Math.hypot(m[Matrix4.M00], m[Matrix4.M10]), y = Math.hypot(m[Matrix4.M01], m[Matrix4.M11]);
        double tolerance = 1e-4 * Math.max(x, Math.abs(m[Matrix4.M22]));
        double turn = m[Matrix4.M00] * m[Matrix4.M11] - m[Matrix4.M01] * m[Matrix4.M10];
        return x > 0 && m[Matrix4.M22] > 0 && Math.abs(x - y) <= tolerance
              && Math.abs(m[Matrix4.M02]) <= tolerance && Math.abs(m[Matrix4.M12]) <= tolerance
              && Math.abs(m[Matrix4.M20]) <= tolerance && Math.abs(m[Matrix4.M21]) <= tolerance
              // Perpendicular x and y columns: a turn, or with a negative determinant a turn of a mirror image; not a shear.
              && Math.abs(m[Matrix4.M00] * m[Matrix4.M01] + m[Matrix4.M10] * m[Matrix4.M11]) <= tolerance * x
              && (turn > 0 || mirrors && turn < 0)
              && m[Matrix4.M30] == 0 && m[Matrix4.M31] == 0 && m[Matrix4.M32] == 0 && m[Matrix4.M33] == 1;
    }

    /** Whether the transform reflects, which reverses its triangles' winding. */
    static boolean mirrored(Matrix4 transform) {
        return transform.det3x3() < 0;
    }

    /**
     * The x column gives the turn and the horizontal scale; a mirror image's y column is the turned y axis reversed,
     * which the scale's sign carries. A falling piece's last float names what moves it ({@code owner}).
     */
    static void append(FloatArray data, Matrix4 transform, int owner) {
        float[] m = transform.val;
        float horizontal = (float) Math.hypot(m[Matrix4.M00], m[Matrix4.M10]);
        // Two four-float adds: the varargs addAll would allocate an array per instance, every pass.
        data.add(m[Matrix4.M03], m[Matrix4.M13], m[Matrix4.M23], mirrored(transform) ? -horizontal : horizontal);
        data.add(m[Matrix4.M00] / horizontal, m[Matrix4.M10] / horizontal, m[Matrix4.M22], owner);
    }

    /**
     * The units of the instanced vertex stages' samplers: the pose rows (GpuPoses) and the GPU-driven path's piece
     * records (GpuIndirectInstances), which only these stages read.
     */
    static void registerPiece(BaseShader shader) {
        // The pose rows stay on a unit of their own, which only this vertex stage reads (GpuPoses), and so do the
        // GPU-driven path's piece records (GpuIndirectInstances); from GLSL 4.20 on their bindings are explicit too.
        shader.register("u_poses", new BaseShader.GlobalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                target.set(id, GpuPoses.UNIT);
            }
        });
        shader.register("u_pieceRecords", new BaseShader.GlobalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                target.set(id, GpuIndirectInstances.RECORDS_UNIT);
            }
        });
    }

    static boolean instanced(Renderable renderable) {
        return renderable.material != null && renderable.material.has(Instanced.TYPE);
    }

    /** The instanced vertex stages' inputs and functions: an instance's placement, then the rest (tree-instances.glsl). */
    private static String instanceSource() {
        return GpuShaderSource.read("instance-place.glsl") + "\n" + GpuShaderSource.read("tree-instances.glsl");
    }

    /** The scene vertex shader, placing each vertex by its tree's instance data instead of the world transform. */
    static String vertex(String source) {
        source = insert(source, MAIN, GpuDestruction.defines() + instanceSource() + "\n" + MAIN);
        source = insert(source, "vec4 pos = u_worldTrans * vec4(a_position, 1.0);",
              "fallBegin();\n#ifdef impostorFlag\nvec4 pos = vec4(impostorPosition(a_position), 1.0);\n#else\n"
                    + "vec4 pos = vec4(instancePosition(a_position), 1.0);\n#endif\n"
                    + "#ifdef levelDissolveFlag\nv_levelDissolve = instanceDissolve();\n#endif\n");
        // A recolourable model's placement passes its colours in the instance data instead of the uniform.
        source = insert(source, "slotColour(a_color.rgb, a_colourSlot, u_slotColours)", "slotColour(a_color.rgb, a_colourSlot, a_instance2)");
        // An impostor's quad stands where it shows, at the depth of its plant's front (tree-instances.glsl).
        source = insert(source, "gl_Position = u_projViewTrans * pos;",
              "gl_Position = u_projViewTrans * pos;\n#ifdef impostorFlag\ngl_Position.z = impostorFrontZ(a_position, gl_Position);\n#endif\n");
        // A material mapped along the board keeps its place on a falling piece (tree-instances.glsl, fallRest), its way
        // turned as the instance stands.
        if (source.contains(GpuUnitShader.BOARD_REST)) {
            source = insert(source, GpuUnitShader.BOARD_REST, "vec2 boardRest = fallRest(pos.xyz).xy;");
            source = insert(source, GpuUnitShader.BOARD_WAY, "vec2 boardWay = instanceTurn(vec3(a_texCoord0, 0.0)).xy;");
        }
        // An impostor's quad faces the camera; its texels carry their own normals (impostor-sample.glsl).
        return insert(source, "vec3 normal = normalize(u_normalMatrix * a_normal);",
              "\n#ifdef impostorFlag\nvec3 normal = impostorToward();\n#else\nvec3 normal = instanceNormal(a_normal);\n#endif\n");
    }

    /**
     * A depth pass that also draws instanced trees: each of its shaders renders either instanced renderables or the
     * others, never both. Instanced trees use the plain configuration with the instanced vertex shader. Its stages
     * are depth.vert and depth.frag unless the configuration names them.
     */
    static DepthShaderProvider depthProvider(DepthShader.Config plain) {
        return depthProvider(plain, shader -> { });
    }

    /** {@code tables}: registers the falls' texture and clock (GpuLavaLighting#register) where pieces fall. */
    static DepthShaderProvider depthProvider(DepthShader.Config plain, Consumer<DepthShader> tables) {
        String plainVertex = plain.vertexShader != null ? plain.vertexShader : GpuShaderSource.read("depth.vert");
        String plainFragment = plain.fragmentShader != null ? plain.fragmentShader : GpuShaderSource.read("depth.frag");
        DepthShader.Config trees = new DepthShader.Config(depthVertex(plainVertex), plainFragment);
        trees.defaultCullFace = plain.defaultCullFace;
        trees.defaultDepthFunc = plain.defaultDepthFunc;
        trees.depthBufferOnly = plain.depthBufferOnly;
        trees.defaultAlphaTest = plain.defaultAlphaTest;
        return new DepthShaderProvider(plain) {
            @Override
            protected Shader createShader(Renderable renderable) {
                boolean instanced = instanced(renderable);
                DepthShader.Config chosen = instanced ? trees : plain;
                // A tree impostor's quad turns toward the light and shows its frames (impostor-sample.glsl).
                boolean impostor = instanced && GpuTerrain.impostor(renderable.material);
                String vertex = instanced ? trees.vertexShader : plainVertex;
                DepthShader result = new DepthShader(renderable, chosen, GpuGlsl.compile("GPU shadow depth",
                      DepthShader.createPrefix(renderable, chosen) + (impostor ? "#define impostorFlag\n" : ""),
                      vertex, plainFragment)) {
                    @Override
                    public boolean canRender(Renderable other) {
                        return instanced(other) == instanced
                              && (instanced && GpuTerrain.impostor(other.material)) == impostor && super.canRender(other);
                    }
                };
                GpuShadowLight.register(result);
                registerPiece(result);
                tables.accept(result);
                return result;
            }
        };
    }

    private static String depthVertex(String source) {
        source = insert(source, MAIN, "uniform mat4 u_projViewTrans;\n" + GpuDestruction.defines()
              + instanceSource() + "\n" + MAIN);
        return insert(source, "vec4 pos = u_projViewWorldTrans * vec4(a_position, 1.0);",
              "\nfallBegin();\n#ifdef impostorFlag\nvec4 pos = u_projViewTrans * vec4(impostorPosition(a_position), 1.0);\n#else\n"
                    + "vec4 pos = u_projViewTrans * vec4(instancePosition(a_position), 1.0);\n#endif\n");
    }

    private static String insert(String source, String anchor, String replacement) {
        return GpuShaderSource.replaceOnce(source, anchor, replacement, "tree instancing");
    }
}
