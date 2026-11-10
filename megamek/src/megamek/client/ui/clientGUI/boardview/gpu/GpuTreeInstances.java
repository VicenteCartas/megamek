/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.RenderableProvider;
import com.badlogic.gdx.graphics.g3d.Shader;
import com.badlogic.gdx.graphics.g3d.model.MeshPart;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.model.NodePart;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DepthShader;
import com.badlogic.gdx.graphics.g3d.utils.DepthShaderProvider;
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.IntArray;
import com.badlogic.gdx.utils.Pool;
import com.badlogic.gdx.utils.ShortArray;

/**
 * Trees, modular building parts and placed objects drawn with OpenGL instancing. Each model at each detail level is
 * held once; a placement adds only its place, turn, size and mirror, so the geometry in memory does not grow with the
 * number of trees.
 * Each species and detail level keeps one buffer of the trees at that level, in board order; each pass draws the ranges
 * of the chunks it sees, so panning uploads nothing, and a zoom uploads only the buffers whose trees changed level. A
 * chunk's trees upload again when the chunk is replaced with different trees. Building and object parts still gather
 * per pass.
 */
final class GpuTreeInstances implements RenderableProvider, Disposable {
    /** Marks the materials of instanced renderables, so the shader providers pick the instanced shaders. */
    static final class Instanced extends Attribute {
        static final long TYPE = register("boardInstanced");

        Instanced() { super(TYPE); }

        @Override
        public Attribute copy() { return new Instanced(); }

        @Override
        public int compareTo(Attribute other) { return Long.compare(type, other.type); }
    }

    /** The draw passes that gather building parts; each keeps its own part buffers, as its parts differ. */
    enum Pass { COLOUR, DEPTH, SHADOW, FADE }

    /**
     * Floats per tree: x, y, z and the horizontal scale, negative for a mirror image (its model's y axis reversed before
     * the turn, tree-instances.glsl); the cosine and sine of its turn, its vertical scale, and its dissolve: 0 at rest,
     * else when a tree began dissolving from one detail level into another, positive at the level it takes and negative
     * at the one it leaves ({@link Stand#detail}).
     */
    static final int STRIDE = 8;
    private static final int DISSOLVE = 7;
    private static final String MAIN = "void main() {";

    /** One model or module part: an instanced copy of its mesh, a renderable per part and the instance buffer. */
    private final class Batch implements Disposable {
        final GpuInstancedMesh mesh;
        final List<Renderable> parts = new ArrayList<>();
        /** A tree model's parts that fade, as Pass.FADE draws them. */
        final List<Renderable> fades = new ArrayList<>();
        final FloatArray data = new FloatArray();
        /** What the instance buffer holds. */
        final FloatArray uploaded = new FloatArray();
        /** Floats per instance: {@link #STRIDE}, and a recolourable model's four slot colours after them. */
        final int stride;
        int capacity = 64;

        Batch(Model model) {
            mesh = new GpuInstancedMesh(model.meshes.first());
            // A stand's trees sway in a blast's wind; a felled stand's fall (tree-instances.glsl).
            mesh.piece = new float[] { 0, 0, 0, 0, 0, 0, felled ? GpuInstancedMesh.FELLED : GpuInstancedMesh.STANDING };
            stride = STRIDE;
            mesh.enableInstancedRendering(false, capacity, attributes(false));
            forEachPart(model.nodes, part -> {
                Renderable renderable = new Renderable();
                renderable.meshPart.set(part.meshPart);
                renderable.meshPart.mesh = mesh;
                renderable.material = instancedMaterial(part.material);
                parts.add(renderable);
                if (GpuCutoutFade.fades(renderable.material)) {
                    Renderable fade = new Renderable();
                    fade.meshPart.set(renderable.meshPart);
                    fade.material = GpuCutoutFade.fadeMaterial(renderable.material);
                    fades.add(fade);
                }
            });
        }

        /**
         * A shared building module or object part; only this indexed range needs an instanced vertex buffer. A batch of
         * mirror images ({@code mirrored}) takes the clockwise winding as the front ({@link GpuInstancedMesh#mirrored}).
         */
        Batch(Renderable source, boolean mirrored) {
            MeshBuilder builder = compacted(source.meshPart);
            mesh = new GpuInstancedMesh(builder.getNumVertices(), builder.getNumIndices(), builder.getAttributes());
            mesh.mirrored = mirrored;
            builder.end(mesh);
            boolean colours = RigidGlb.colourSlotted(mesh.getVertexAttributes());
            stride = colours ? STRIDE + 4 : STRIDE;
            mesh.enableInstancedRendering(false, capacity, attributes(colours));
            Renderable part = new Renderable();
            part.meshPart.set(source.meshPart);
            part.meshPart.mesh = mesh;
            part.meshPart.offset = 0;
            part.material = instancedMaterial(source.material);
            parts.add(part);
        }

        void upload() {
            if (data.equals(uploaded)) { return; }
            upload(data);
            uploaded.clear();
            uploaded.addAll(data);
        }

        void upload(FloatArray instances) {
            int count = instances.size / stride;
            if (count > capacity) {
                capacity = Math.max(count, capacity * 2);
                mesh.disableInstancedRendering();
                mesh.enableInstancedRendering(false, capacity, attributes(stride > STRIDE));
            }
            mesh.setInstanceData(instances.items, 0, instances.size);
            uploads++;
        }

        @Override
        public void dispose() { mesh.dispose(); }
    }

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

    /** A part's material in a batch: its own copy, marked so the shader providers pick the instanced shaders. */
    static Material instancedMaterial(Material material) {
        Material result = new Material(material);
        result.set(new Instanced());
        return result;
    }

    /** Each node part of these nodes and their children, in the order a batch draws them. */
    private static void forEachPart(Iterable<Node> nodes, Consumer<NodePart> action) {
        for (Node node : nodes) {
            for (NodePart part : node.parts) { action.accept(part); }
            forEachPart(node.getChildren(), action);
        }
    }

    /**
     * The parts a stand of this species draws at every detail level, each as its batch's material and vertex layout,
     * for programs built before the stand first draws (GpuTerrain's warm-up, with the depth pre-pass).
     */
    void speciesParts(String species, BiConsumer<Material, VertexAttributes> action) {
        for (int level = 0; level < TreeLod.LEVELS; level++) {
            Model model = models.apply(species, level);
            VertexAttributes layout = model.meshes.first().getVertexAttributes();
            forEachPart(model.nodes, part -> action.accept(instancedMaterial(part.material), layout));
        }
    }

    /** A batch's actual instance layout, including colour slots when its vertex layout addresses them. */
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
        }

        /** No trees; the scale stays for the next ones (GpuTerrain.Chunk#restand). */
        void clear() {
            trees.clear();
            diameters.clear();
            starts.clear();
            levels.clear();
            dissolves.clear();
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
            return result;
        }

        /** Whether a species' tree is dissolving: it holds its level until the dissolve ends. */
        private boolean holds(Dissolve dissolve, int tree) {
            return dissolve != null && dissolve.leaving[tree] >= 0 && now - dissolve.began[tree] < duration;
        }
    }

    /**
     * One species at one detail level: every chunk's trees of that species in one buffer, in board order, and the
     * ranges of the chunks a pass draws at this level.
     */
    private final class Species implements Disposable {
        final Batch batch;
        // Chunk arrays are replaced, never edited: identity, then contents, identify an unchanged chunk.
        final List<FloatArray> current = new ArrayList<>(), previous = new ArrayList<>();
        final IntArray drawn = new IntArray(), starts = new IntArray(), ranges = new IntArray();
        final FloatArray data = new FloatArray();
        boolean gathered;

        Species(Model model) { batch = new Batch(model); }

        void begin() { current.clear(); drawn.clear(); gathered = false; }

        void add(FloatArray trees, boolean visible) {
            current.add(trees);
            drawn.add(visible ? 1 : 0);
        }

        boolean upload() {
            boolean changed = current.size() != previous.size();
            for (int i = 0; !changed && i < current.size(); i++) {
                changed = current.get(i) != previous.get(i) && !current.get(i).equals(previous.get(i));
            }
            previous.clear(); previous.addAll(current);
            if (changed) {
                data.clear();
                starts.clear();
                for (FloatArray trees : current) {
                    starts.add(data.size / STRIDE);
                    data.addAll(trees);
                }
                starts.add(data.size / STRIDE);
                // A level no tree takes draws nothing: its buffer keeps what it held.
                if (data.size > 0) { batch.upload(data); }
            }
            // Chunks lie in board order, so a column of visible chunks is one contiguous range and one draw.
            ranges.clear();
            int count = 0;
            for (int i = 0; i < current.size(); i++) {
                int size = starts.get(i + 1) - starts.get(i);
                if (drawn.get(i) == 0 || size == 0) { continue; }
                if (ranges.size > 0 && ranges.get(ranges.size - 2) + ranges.get(ranges.size - 1) == starts.get(i)) {
                    ranges.incr(ranges.size - 1, size);
                } else {
                    ranges.add(starts.get(i), size);
                }
                count += size;
            }
            batch.mesh.drawRanges(ranges);
            return count > 0;
        }

        @Override
        public void dispose() { batch.dispose(); }
    }

    /**
     * A part's own copy, built but not ended: its indexed range compacted, as parts share a GLB vertex buffer. Its
     * vertices are numbered as its indices first reach them, which a broken part's fragments' table counts on
     * (KitFragments).
     */
    static MeshBuilder compacted(MeshPart part) {
        FloatArray vertices = new FloatArray();
        ShortArray indices = new ShortArray();
        GpuPropBatch.copyVertices(part.mesh, part.offset, part.size, vertices, indices);
        MeshBuilder builder = new MeshBuilder();
        builder.begin(part.mesh.getVertexAttributes(), part.primitiveType);
        builder.addMesh(vertices.items, indices.items, 0, indices.size);
        return builder;
    }

    private final BiFunction<String, Integer, Model> models;
    /** Whether its trees are felled ones (GpuDestruction), whose instances name their slots instead of a dissolve. */
    private final boolean felled;
    /** Whether every pass gathers the same placements (GpuDestruction's pieces): a part's passes share one batch. */
    private final boolean samePasses;
    private final Map<String, Species[]> species = new HashMap<>();
    private final List<Species> stands = new ArrayList<>();
    /** A part's batch key: mirror images draw in their own batch, whose front face is flipped. */
    private record Part(Mesh mesh, int offset, int size, Material material, boolean mirrored) { }
    private final Map<Part, Batch[]> sharedParts = new HashMap<>();
    // Persistent chunk snapshots need no per-frame material/range keys; values do not retain their weak keys.
    private final Map<Renderable, Batch[]> sharedLookups = new WeakHashMap<>();
    private final List<Batch> gathered = new ArrayList<>();
    private Pass pass = Pass.COLOUR;
    private long uploads;

    /** models: loads a tree model, already marked for the foliage shader, by asset name. */
    GpuTreeInstances(BiFunction<String, Integer, Model> models) { this(models, false); }

    /** With {@code felled} its trees fall (GpuDestruction): their stands never dissolve between levels. */
    GpuTreeInstances(BiFunction<String, Integer, Model> models, boolean felled) { this(models, felled, false); }

    /**
     * With {@code samePasses} every pass gathers the same placements, and a part keeps one batch for them all: one copy
     * of its mesh, built once, and one upload of its instances.
     */
    GpuTreeInstances(BiFunction<String, Integer, Model> models, boolean felled, boolean samePasses) {
        this.models = models;
        this.felled = felled;
        this.samePasses = samePasses;
    }

    /** Starts gathering one pass's trees and building parts. */
    void begin(Pass next) {
        for (Batch batch : gathered) { batch.data.clear(); }
        gathered.clear();
        for (Species stand : stands) { stand.begin(); }
        stands.clear();
        pass = next;
    }

    /**
     * Adds a chunk's trees, each at its own detail level ({@link Stand#detail}), in board order, whether the pass sees
     * the chunk or not: a chunk's place in each level's buffer stays fixed while its trees there are unchanged, and only
     * the visible chunks draw.
     */
    void add(Stand stand, boolean visible) {
        for (String name : stand.trees.keySet()) {
            FloatArray[] runs = stand.levels(name);
            Species[] levels = species.computeIfAbsent(name, key -> new Species[TreeLod.LEVELS]);
            for (int lod = 0; lod < levels.length; lod++) {
                if (levels[lod] == null) {
                    if (!visible || runs[lod].size == 0) { continue; }
                    levels[lod] = new Species(models.apply(name, lod));
                }
                gather(levels[lod]);
                levels[lod].add(runs[lod], visible);
            }
        }
    }

    /** Adds all of a chunk's trees at one detail level: the shadow map's, chosen from its texel (GpuTerrain#renderDepth). */
    void add(Stand stand, int level, boolean visible) {
        for (Map.Entry<String, FloatArray> entry : stand.trees.entrySet()) {
            Species[] levels = species.computeIfAbsent(entry.getKey(), key -> new Species[TreeLod.LEVELS]);
            for (int lod = 0; lod < levels.length; lod++) {
                if (levels[lod] == null) {
                    if (!visible || lod != level) { continue; }
                    levels[lod] = new Species(models.apply(entry.getKey(), lod));
                }
                gather(levels[lod]);
                levels[lod].add(lod == level ? entry.getValue() : NONE, visible && lod == level);
            }
        }
    }

    private static final FloatArray NONE = new FloatArray(1);

    private void gather(Species level) {
        if (!level.gathered) { level.gathered = true; stands.add(level); }
    }

    /**
     * Building module ranges and object parts borrow their meshes; placements add only transforms, using the same draw
     * path as trees. Each transform must be {@link #instanceable}.
     */
    void add(Array<Renderable> parts) {
        for (Renderable source : parts) { add(source); }
    }

    /**
     * A chunk's parts with their instance data, by batch: what {@link #add(Renderable)} finds for each part every pass,
     * ready until the chunk snapshots its parts again.
     */
    static final class Placements {
        private final List<Renderable> samples = new ArrayList<>();
        private final List<Batch[]> batches = new ArrayList<>();
        private final List<FloatArray> data = new ArrayList<>();
        /** Whether each batch's parts are falling pieces, which turn about their sample's box. */
        private final List<Boolean> pieces = new ArrayList<>();
    }

    Placements placements(Array<Renderable> parts) { return placements(parts, null); }

    /**
     * Falling pieces ({@link GpuDestruction}) with their hexes' slots + 1 ({@code owners}), which their instances carry
     * (a_instance1.w); a piece's mesh part's box is its node's, about which it turns.
     */
    Placements placements(Array<Renderable> parts, IntArray owners) {
        Placements result = new Placements();
        Map<Batch[], FloatArray> data = new IdentityHashMap<>();
        for (int i = 0; i < parts.size; i++) {
            Renderable source = parts.get(i);
            int owner = owners == null ? 0 : owners.get(i);
            // Looked up once, here: the weak lookups hold only the parts gathered one by one every pass (#add).
            Batch[] passes = part(source);
            FloatArray instances = data.get(passes);
            if (instances == null) {
                data.put(passes, instances = new FloatArray());
                result.samples.add(source);
                result.batches.add(passes);
                result.data.add(instances);
                result.pieces.add(owner > 0);
            }
            append(instances, source.worldTransform, owner);
            if (RigidGlb.colourSlotted(source.meshPart.mesh.getVertexAttributes())) {
                instances.addAll(RigidGlb.slotColours(source.userData));
            }
        }
        return result;
    }

    /** These placements, in this pass. */
    void add(Placements placed) {
        for (int i = 0; i < placed.batches.size(); i++) {
            Batch batch = batch(placed.batches.get(i), placed.samples.get(i));
            if (batch.data.isEmpty()) { gathered.add(batch); }
            if (placed.pieces.get(i) && batch.mesh.piece == null) { batch.mesh.piece = GpuInstancedMesh.piece(placed.samples.get(i)); }
            batch.data.addAll(placed.data.get(i));
        }
    }

    private Batch[] batches(Renderable source) { return sharedLookups.computeIfAbsent(source, this::part); }

    /** A part's batches by its key: its mesh range, its material and whether it is a mirror image. */
    private Batch[] part(Renderable value) {
        return sharedParts.computeIfAbsent(new Part(value.meshPart.mesh, value.meshPart.offset, value.meshPart.size,
              value.material, mirrored(value.worldTransform)), ignored -> new Batch[Pass.values().length]);
    }

    /** This pass's batch among a part's. */
    private Batch batch(Batch[] passes, Renderable source) {
        int index = samePasses ? 0 : pass.ordinal();
        Batch batch = passes[index];
        if (batch == null) { passes[index] = batch = new Batch(source, mirrored(source.worldTransform)); }
        return batch;
    }

    /** One part's placement; a part that gathers no placement in a pass costs no draw. */
    void add(Renderable source) {
        Batch batch = batch(batches(source), source);
        if (batch.data.isEmpty()) { gathered.add(batch); }
        append(batch.data, source.worldTransform, 0);
        // Every colouring of a recolourable model shares this batch: each placement adds its colours.
        if (batch.stride > STRIDE) { batch.data.addAll(RigidGlb.slotColours(source.userData)); }
    }

    /**
     * Whether a tree's instance data can hold this transform: a place, a turn about z, one positive scale along x and y
     * and a positive vertical scale. Tilted, mirrored, sheared or unevenly stretched placements cannot be instanced in a
     * stand, whose species batches keep the counter-clockwise front face.
     */
    static boolean placeable(Matrix4 transform) {
        return placeable(transform, false);
    }

    /**
     * Whether an object or module part's instance data can hold this transform: a {@link #placeable} one or its mirror
     * image, which {@link #add} draws in a batch of its own with the front face flipped. Tilted, sheared or unevenly
     * stretched placements cannot be instanced.
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
     * which the scale's sign carries. A falling piece's last float names its hex's slot + 1 ({@code owner}).
     */
    private static void append(FloatArray data, Matrix4 transform, int owner) {
        float[] m = transform.val;
        float horizontal = (float) Math.hypot(m[Matrix4.M00], m[Matrix4.M10]);
        // Two four-float adds: the varargs addAll would allocate an array per instance, every pass.
        data.add(m[Matrix4.M03], m[Matrix4.M13], m[Matrix4.M23], mirrored(transform) ? -horizontal : horizontal);
        data.add(m[Matrix4.M00] / horizontal, m[Matrix4.M10] / horizontal, m[Matrix4.M22], owner);
    }

    /**
     * A debris batch's piece for the draw (tree-instances.glsl: u_piece, u_pieceHalf, whose w counts a broken part's
     * fragments, and their table u_fragments); 0 for every other batch.
     */
    static void registerPiece(BaseShader shader) {
        shader.register("u_piece", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                float[] piece = piece(renderable);
                if (piece == null) { target.set(id, 0f, 0f, 0f, 0f); } else { target.set(id, piece[0], piece[1], piece[2], piece[6]); }
            }
        });
        shader.register("u_pieceHalf", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                float[] piece = piece(renderable);
                if (piece != null) {
                    target.set(id, piece[3], piece[4], piece[5], (piece.length - GpuInstancedMesh.PIECE) / 8f);
                }
            }
        });
        shader.register("u_fragments", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                float[] piece = piece(renderable);
                if (piece != null && piece.length > GpuInstancedMesh.PIECE && target.has(id)) {
                    target.program.setUniform4fv(target.loc(id), piece, GpuInstancedMesh.PIECE, piece.length - GpuInstancedMesh.PIECE);
                }
            }
        });
    }

    private static float[] piece(Renderable renderable) {
        return renderable.meshPart.mesh instanceof GpuInstancedMesh instanced ? instanced.piece : null;
    }

    /** Evict buffers for retired building interiors/modules at the scene commit boundary. */
    void retainParts(Set<Mesh> live) {
        Set<Batch[]> evicted = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Batch> disposed = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        sharedParts.entrySet().removeIf(entry -> {
            if (live.contains(entry.getKey().mesh())) { return false; }
            for (Batch batch : entry.getValue()) {
                if (batch != null && disposed.add(batch)) { batch.dispose(); }
            }
            evicted.add(entry.getValue());
            return true;
        });
        if (evicted.isEmpty()) { return; }
        // One search of the gathered batches for all of them, and only the evicted parts' lookups go: every other
        // snapshot would pay its materials' hashes again next frame.
        gathered.removeIf(disposed::contains);
        sharedLookups.values().removeIf(evicted::contains);
    }

    /** Uploads what changed and supplies one renderable per part of every model this pass draws. */
    @Override
    public void getRenderables(Array<Renderable> renderables, Pool<Renderable> pool) {
        for (Species stand : stands) {
            if (pass == Pass.FADE && stand.batch.fades.isEmpty()) { continue; }
            if (stand.upload()) { parts(pass == Pass.FADE ? stand.batch.fades : stand.batch.parts, renderables); }
        }
        for (Batch batch : gathered) {
            batch.upload();
            parts(batch.parts, renderables);
        }
    }

    private static void parts(List<Renderable> parts, Array<Renderable> renderables) {
        for (Renderable part : parts) {
            // Each pass picks its own shader; a depth shader must never be suggested to the colour pass.
            part.shader = null;
            part.environment = null;
            renderables.add(part);
        }
    }

    /** Instance buffer uploads so far; unchanged chunks and unchanged building parts add none. */
    long uploads() { return uploads; }

    /** Bytes of the shared tree/building meshes and of their instance buffers. */
    long bytes() {
        long total = 0;
        List<Batch> all = new ArrayList<>();
        for (Species[] levels : species.values()) { for (Species level : levels) { if (level != null) { all.add(level.batch); } } }
        for (Batch[] passes : sharedParts.values()) { for (Batch batch : passes) { if (batch != null) { all.add(batch); } } }
        for (Batch batch : all) {
            total += (long) batch.mesh.getNumVertices() * batch.mesh.getVertexSize()
                  + (long) batch.mesh.getNumIndices() * Short.BYTES + (long) batch.capacity * batch.stride * Float.BYTES;
        }
        return total;
    }

    @Override
    public void dispose() {
        for (Species[] levels : species.values()) {
            for (Species level : levels) { if (level != null) { level.dispose(); } }
        }
        retainParts(Set.of());
        species.clear();
        stands.clear();
        gathered.clear();
    }

    static boolean instanced(Renderable renderable) {
        return renderable.material != null && renderable.material.has(Instanced.TYPE);
    }

    /** The scene vertex shader, placing each vertex by its tree's instance data instead of the world transform. */
    static String vertex(String source) {
        source = insert(source, MAIN, GpuDestruction.defines() + GpuShaderSource.read("tree-instances.glsl") + "\n" + MAIN);
        source = insert(source, "vec4 pos = u_worldTrans * vec4(a_position, 1.0);",
              "fallBegin();\n#ifdef impostorFlag\nvec4 pos = vec4(impostorPosition(a_position), 1.0);\n#else\n"
                    + "vec4 pos = vec4(instancePosition(a_position), 1.0);\n#endif\n"
                    + "#ifdef treeDissolveFlag\nv_treeDissolve = instanceDissolve();\n#endif\n");
        // A recolourable model's placement passes its colours in the instance data instead of the uniform.
        source = insert(source, "slotColour(a_color.rgb, a_colourSlot, u_slotColours)", "slotColour(a_color.rgb, a_colourSlot, a_instance2)");
        // An impostor's quad stands where it shows, at the depth of its plant's front (tree-instances.glsl).
        source = insert(source, "gl_Position = u_projViewTrans * pos;",
              "gl_Position = u_projViewTrans * pos;\n#ifdef impostorFlag\ngl_Position.z = impostorFrontZ(a_position, gl_Position);\n#endif\n");
        // A material mapped along the board keeps its place on a falling piece (tree-instances.glsl, fallRest).
        String boardUV = "v_modelUV = vec2(pos.x, -pos.y) / u_modelBoardUV;";
        if (source.contains(boardUV)) {
            source = insert(source, boardUV, "v_modelUV = vec2(fallRest(pos.xyz).x, -fallRest(pos.xyz).y) / u_modelBoardUV;");
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
              + GpuShaderSource.read("tree-instances.glsl") + "\n" + MAIN);
        return insert(source, "vec4 pos = u_projViewWorldTrans * vec4(a_position, 1.0);",
              "\nfallBegin();\n#ifdef impostorFlag\nvec4 pos = u_projViewTrans * vec4(impostorPosition(a_position), 1.0);\n#else\n"
                    + "vec4 pos = u_projViewTrans * vec4(instancePosition(a_position), 1.0);\n#endif\n");
    }

    private static String insert(String source, String anchor, String replacement) {
        return GpuShaderSource.replaceOnce(source, anchor, replacement, "tree instancing");
    }
}
