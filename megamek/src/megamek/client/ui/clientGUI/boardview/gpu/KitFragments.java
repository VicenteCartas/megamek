/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.model.MeshPart;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.model.NodePart;
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
import megamek.common.board.Coords;
import megamek.logging.MMLogger;

/**
 * One kit node, or one tree's model, broken for the fall about to bring it down ({@link GpuDestruction}): a node's drawn
 * parts cut together into closed fragments a few metres across ({@link MeepleShatter}), the cuts running across its
 * walls. Each part keeps one range, its fragments one after another, welded within a fragment only, so that a falling
 * part stays one instanced part and each of its vertices finds its fragment's box by its index in the pools' copy
 * (tree-instances.glsl). Every building placing the node shares its cut. Cut on a thread of its own from the kit's CPU
 * data once the fall is known, installed as CPU-only meshes on the render thread, which the fall lists copy their
 * ranges from into the pools (GpuIndirectInstances), and kept after its falls only within a budget ({@link Cuts}).
 * Presentation only.
 */
final class KitFragments implements Disposable {
    private static final MMLogger LOGGER = MMLogger.create(KitFragments.class);
    /** About the side of a fragment, in kit units (83 across a hex, 18 a level). */
    static final float SIDE = 8;
    /**
     * A kit node breaks into at most this many fragments, its walls' cut already a few metres across within it; a
     * structure's own model's node ({@link #shell}) into at most {@link #SHELL_FRAGMENTS}, kit-sized lattice cells
     * over a hex share. Their tables lie in the pose texture's table rows (GpuPoses#table).
     */
    static final int KIT_FRAGMENTS = 16, SHELL_FRAGMENTS = 512;
    /**
     * A shell is cut at two grades of the same lattice ({@link #shell}): cells of {@link #SIDE} (the kit's pieces, for a
     * fall seen up close) and of this many times it (for one farther off). The fall takes the grade its view wants
     * (TerrainFalls#fallPieces); smaller still on screen, it falls whole.
     */
    static final int FINE = 0, COARSE = 1;
    private static final float[] GRADE_CELLS = { 1, 3 };
    /** A tree breaks into at most this many fragments, of at least {@link #TREE_TRIANGLES} triangles each. */
    private static final int TREE_FRAGMENTS = 12, TREE_TRIANGLES = 4;
    /** Rounds of grouping a tree's triangles about their fragments' middles (#split). */
    private static final int TREE_ROUNDS = 8;
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
    /** A part's copy in the pools indexes its vertices with shorts (GpuIndirectInstances). */
    private static final int MESH_VERTICES = 65_536;
    /** Walls facing one way at least this much more than across: its breaks all run across them. */
    private static final float ONE_WAY = .5f;

    /**
     * Nodes and trees are cut here in the order asked, up to three at a time: a hex's base, storey and roof together,
     * as a fall taken before they are in falls whole (TerrainFalls#fallPieces), and a storey of thousands of triangles
     * takes a few hundred milliseconds. At the render thread's own priority: beside the chunk builders of the scene that
     * follows the fall, a lower one starves them. The threads end when nothing has been asked for a while.
     */
    private static final ExecutorService CUTTER = cutter();

    private static ExecutorService cutter() {
        int threads = Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() - 2));
        var pool = new java.util.concurrent.ThreadPoolExecutor(threads, threads, 30, java.util.concurrent.TimeUnit.SECONDS,
              new java.util.concurrent.LinkedBlockingQueue<>(), work -> {
                  var thread = new Thread(work, "Kit fragment cutter");
                  thread.setDaemon(true);
                  return thread;
              });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /** A drawn part's fragments: its range of the fragments' meshes and the table its record draws them by. */
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
    /** Its parts are named by their ranges, not their ids ({@link #shell}). */
    private boolean ranged;

    /**
     * Raised whenever the same node would come out of the cut differently (here or in MeepleShatter): stored cuts of
     * another version are never read.
     */
    private static final int STORE_VERSION = 4;
    /** The most bytes the stored cuts take on disk: past it the least recently read go, once a run ({@link #store}). */
    private static final long STORE_BYTES = 1L << 30;
    /** Where every cut made is kept and read back from; null while cuts are not kept (tests without a board). */
    private static volatile Path store;
    /**
     * Fills the store with the cuts of the kit nodes a board draws up close ({@link #storeLater}), one at a time at the
     * lowest priority: nothing waits for it, and a fall named later reads its cuts back in milliseconds.
     */
    private static final ExecutorService FILLER = filler();

    private static ExecutorService filler() {
        var pool = new java.util.concurrent.ThreadPoolExecutor(1, 1, 30, java.util.concurrent.TimeUnit.SECONDS,
              new java.util.concurrent.LinkedBlockingQueue<>(), work -> {
                  var thread = new Thread(work, "Kit fragment store");
                  thread.setDaemon(true);
                  thread.setPriority(Thread.MIN_PRIORITY);
                  return thread;
              });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /**
     * Keeps every cut made from now on in {@code directory}, a file a node named by a digest of what its cut reads, and
     * reads a node's cut back from there instead of making it again: on this machine a node is cut once, whatever
     * board, run or level of detail asks. The oldest go once the directory passes {@link #STORE_BYTES}. Null: cuts are
     * no longer kept.
     */
    static void store(Path directory) {
        store = directory;
        if (directory != null) { FILLER.execute(() -> prune(directory)); }
    }

    private static void prune(Path directory) {
        if (!Files.isDirectory(directory)) { return; }
        record Kept(Path file, long read, long size) { }
        List<Kept> kept = new ArrayList<>();
        try (var files = Files.newDirectoryStream(directory, "*.cut")) {
            for (Path file : files) {
                var attributes = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
                kept.add(new Kept(file, attributes.lastModifiedTime().toMillis(), attributes.size()));
            }
            kept.sort((a, b) -> Long.compare(b.read(), a.read()));
            long total = 0;
            for (Kept cut : kept) {
                total += cut.size();
                if (total > STORE_BYTES) { Files.deleteIfExists(cut.file()); }
            }
        } catch (IOException failure) {
            LOGGER.warn(failure, "The kit cuts kept in {} could not be looked over", directory);
        }
    }

    /**
     * Makes the source's cut and keeps it, in the background, unless it was asked before: for the nodes a board draws up
     * close, so that their falls find their cuts made. Render thread.
     */
    static void storeLater(Level.Source source) {
        if (store == null || !source.level().stored.add(source.part())) { return; }
        FILLER.execute(() -> {
            try {
                cut(source);
            } catch (RuntimeException failure) {
                LOGGER.warn(failure, "Kit {}, the node of {}: its cut could not be made ahead", source.level().data.id,
                      source.part());
            }
        });
    }

    /**
     * The file that keeps the cut of these nodes, by a digest of everything the cut reads ({@code kind}: how it is cut
     * where that is not a kit node's way); null while none is kept.
     */
    private static Path stored(List<Input> inputs, String kind) {
        Path directory = store;
        if (directory == null) { return null; }
        java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException missing) {
            throw new IllegalStateException("Every Java platform provides SHA-256", missing);
        }
        var text = new StringBuilder(kind).append(STORE_VERSION).append(' ').append(SIDE).append(' ')
              .append(KIT_FRAGMENTS).append(' ').append(VERTEX_SHARE).append(' ').append(SMALL_EXTRA);
        for (Input input : inputs) {
            text.append(' ').append(input.name).append(' ').append(input.layout);
            for (int index = 0; index < input.ids.size(); index++) {
                text.append(' ').append(input.ids.get(index)).append(' ').append(input.materials.get(index).id);
            }
        }
        digest.update(text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (Input input : inputs) {
            for (FloatArray records : input.triangles) { digest.update(bytes(records.items, records.size)); }
        }
        return directory.resolve(java.util.HexFormat.of().formatHex(digest.digest(), 0, 16) + ".cut");
    }

    private static byte[] bytes(float[] values, int count) {
        byte[] result = new byte[count * Float.BYTES];
        java.nio.ByteBuffer.wrap(result).asFloatBuffer().put(values, 0, count);
        return result;
    }

    /** Keeps this cut, not yet installed, in {@code file}; a cut that could not be kept is made again next time. */
    private void write(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Path partial = file.resolveSibling(file.getFileName() + "." + Thread.currentThread().threadId() + ".part");
            try (var out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(partial), 1 << 16))) {
                out.writeInt(STORE_VERSION);
                out.writeInt(layouts.size());
                for (int mesh = 0; mesh < layouts.size(); mesh++) {
                    VertexAttributes layout = layouts.get(mesh);
                    out.writeInt(layout.size());
                    for (VertexAttribute attribute : layout) {
                        out.writeInt(attribute.usage);
                        out.writeInt(attribute.numComponents);
                        out.writeInt(attribute.type);
                        out.writeBoolean(attribute.normalized);
                        out.writeInt(attribute.unit);
                        out.writeUTF(attribute.alias);
                    }
                    out.writeInt(vertices.get(mesh).size);
                    out.write(bytes(vertices.get(mesh).items, vertices.get(mesh).size));
                    ShortArray index = indices.get(mesh);
                    out.writeInt(index.size);
                    byte[] shorts = new byte[index.size * Short.BYTES];
                    java.nio.ByteBuffer.wrap(shorts).asShortBuffer().put(index.items, 0, index.size);
                    out.write(shorts);
                }
                out.writeInt(ranges.size());
                for (var range : ranges.entrySet()) {
                    out.writeUTF(range.getKey());
                    out.writeInt(range.getValue().mesh());
                    out.writeInt(range.getValue().offset());
                    out.writeInt(range.getValue().count());
                    out.writeInt(range.getValue().table().length);
                    out.write(bytes(range.getValue().table(), range.getValue().table().length));
                }
            }
            Files.move(partial, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn(failure, "A kit cut could not be kept in {}", file);
        }
    }

    /** Reads this cut back from {@code file}; false, and nothing read, where it is not there or not this version's. */
    private boolean read(Path file) {
        if (!Files.isRegularFile(file)) { return false; }
        try (var in = new java.io.DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(file), 1 << 16))) {
            if (in.readInt() != STORE_VERSION) { return false; }
            int meshes = in.readInt();
            for (int mesh = 0; mesh < meshes; mesh++) {
                VertexAttribute[] attributes = new VertexAttribute[in.readInt()];
                for (int index = 0; index < attributes.length; index++) {
                    int usage = in.readInt(), components = in.readInt(), type = in.readInt();
                    boolean normalized = in.readBoolean();
                    int unit = in.readInt();
                    attributes[index] = new VertexAttribute(usage, components, type, normalized, in.readUTF(), unit);
                }
                layouts.add(new VertexAttributes(attributes));
                byte[] floats = in.readNBytes(in.readInt() * Float.BYTES);
                float[] values = new float[floats.length / Float.BYTES];
                java.nio.ByteBuffer.wrap(floats).asFloatBuffer().get(values);
                vertices.add(new FloatArray(values));
                byte[] shorts = in.readNBytes(in.readInt() * Short.BYTES);
                short[] index = new short[shorts.length / Short.BYTES];
                java.nio.ByteBuffer.wrap(shorts).asShortBuffer().get(index);
                indices.add(new ShortArray(index));
            }
            int count = in.readInt();
            for (int index = 0; index < count; index++) {
                String id = in.readUTF();
                int mesh = in.readInt(), offset = in.readInt(), size = in.readInt();
                byte[] floats = in.readNBytes(in.readInt() * Float.BYTES);
                float[] table = new float[floats.length / Float.BYTES];
                java.nio.ByteBuffer.wrap(floats).asFloatBuffer().get(table);
                ranges.put(id, new Range(mesh, offset, size, table));
            }
            // Read again lately: it outlives those that were not.
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
            return true;
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn(failure, "The kit cut kept in {} could not be read: it is made again", file);
            layouts.clear();
            vertices.clear();
            indices.clear();
            ranges.clear();
            return false;
        }
    }

    private KitFragments() { }

    /**
     * A kit level as its nodes' cuts read it. {@code skipped}: the materials no falling piece draws (#floor, glass). The
     * render thread names a node by the first mesh part its placement draws ({@link #source}); what a cut reads, each
     * mesh part's data and the node that alone draws it, is worked out on a cutter thread before the level's first cut.
     */
    static final class Level {
        private final RigidGlb.Data data;
        private final Set<String> skipped;
        /** The render thread's: one source for all the placements of a node. */
        private final Map<String, Source> sources = new HashMap<>();
        /** The render thread's: the mesh parts whose nodes' cuts were asked ahead for the store (#storeLater). */
        private final Set<String> stored = new HashSet<>();
        private Map<String, ModelMesh> meshOf;
        private Map<String, ModelMeshPart> partOf;
        private Map<String, ModelNode> nodeOf;

        /** One of the level's nodes as what is cut: the node that draws the mesh part {@code part}. */
        record Source(Level level, String part) { }

        Level(RigidGlb.Data level, Set<String> skipped) {
            data = level;
            this.skipped = Set.copyOf(skipped);
        }

        /**
         * The kit's node that {@code drawn}, a placement's copy of it (GpuBuilding#instance), stands for, as the source of
         * its cut; null for a node that draws nothing itself. Render thread.
         */
        Source source(Node drawn) {
            return drawn.parts.size == 0 ? null
                  : sources.computeIfAbsent(drawn.parts.first().meshPart.id, part -> new Source(this, part));
        }

        /** The node that alone draws the mesh part; null where other nodes draw it too: it never breaks. Any thread. */
        ModelNode node(String part) {
            index();
            return nodeOf.get(part);
        }

        private synchronized void index() {
            if (nodeOf != null) { return; }
            Map<String, ModelMesh> meshes = new HashMap<>();
            Map<String, ModelMeshPart> parts = new HashMap<>();
            // A node part draws the first mesh part of its id, as libGDX's Model binds them. Index loops: libGDX's Array
            // reuses its iterator, and the kit's data is read by other threads too (ConnectedKit).
            for (int index = 0; index < data.meshes.size; index++) {
                ModelMesh mesh = data.meshes.get(index);
                for (ModelMeshPart part : mesh.parts) {
                    if (parts.putIfAbsent(part.id, part) == null) { meshes.put(part.id, mesh); }
                }
            }
            Map<String, Integer> drawn = new HashMap<>();
            Map<String, ModelNode> nodes = new HashMap<>();
            for (int index = 0; index < data.nodes.size; index++) { collect(data.nodes.get(index), drawn, nodes); }
            // A mesh part another node draws too is no node's own.
            nodes.keySet().removeIf(part -> drawn.get(part) > 1);
            meshOf = meshes;
            partOf = parts;
            nodeOf = nodes;
        }

        private static void collect(ModelNode node, Map<String, Integer> drawn, Map<String, ModelNode> nodes) {
            if (node.parts != null) {
                for (ModelNodePart part : node.parts) {
                    drawn.merge(part.meshPartId, 1, Integer::sum);
                    nodes.put(part.meshPartId, node);
                }
            }
            if (node.children != null) {
                for (ModelNode child : node.children) { collect(child, drawn, nodes); }
            }
        }
    }

    /** Cuts the source's node on a cutter thread; cancelled while it waits its turn, it never runs. */
    static CompletableFuture<KitFragments> cutLater(Level.Source source) {
        var cut = CompletableFuture.supplyAsync(() -> cut(source), CUTTER);
        cut.whenComplete((done, failure) -> {
            if (failure != null && !(failure instanceof java.util.concurrent.CancellationException)) {
                LOGGER.warn(failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                      ? failure.getCause() : failure, "Kit {}, the node of {}: it falls whole, its cut failed",
                      source.level().data.id, source.part());
            }
        });
        return cut;
    }

    /** The source's node as fragments ({@link #cut(Level, ModelNode)}); none where no node alone draws its part. */
    static KitFragments cut(Level.Source source) {
        ModelNode node = source.level().node(source.part());
        KitFragments result = node == null ? new KitFragments() : cut(source.level(), node);
        // Read back or written: the store holds it now.
        if (store != null) { ON_DISK.put(source, Boolean.TRUE); }
        return result;
    }

    /**
     * {@code node} of {@code level} as fragments, where its parts break into at least two within the bounds: at most
     * {@link #KIT_FRAGMENTS}, at most {@link #VERTEX_SHARE} times its vertices (or {@link #SMALL_EXTRA} more),
     * no part over a batch's vertices, and no part another node draws too; else none, and it falls whole. Any thread: it
     * reads the kit's data only.
     */
    static KitFragments cut(Level level, ModelNode node) {
        level.index();
        var result = new KitFragments();
        result.add(node, level);
        return result;
    }

    /**
     * A structure drawn from a model of its own (an authored building's share, a dome, a wall, a single-model building)
     * cut as a kit node is ({@link #breakUp}), every node of its model apart, into at most {@link #SHELL_FRAGMENTS} a node
     * of about {@code side} model units. Its glass ({@code clear}, material ids) breaks with it and never closes a cut.
     * The model's meshes are copied here, on the thread that owns them; the supplier cuts, or reads the cut back from the
     * store, on any thread. A part another node draws too, or a node of two layouts, falls whole.
     */
    static Supplier<KitFragments> shell(Model model, float side, Set<String> clear, int grade) {
        Map<MeshPart, Integer> uses = new IdentityHashMap<>();
        List<Node> nodes = new ArrayList<>();
        nodes(model.nodes, nodes);
        for (Node node : nodes) {
            for (NodePart part : node.parts) { uses.merge(part.meshPart, 1, Integer::sum); }
        }
        Map<Mesh, float[]> vertices = new IdentityHashMap<>();
        Map<Mesh, short[]> orders = new IdentityHashMap<>();
        List<Input> inputs = new ArrayList<>();
        for (Node node : nodes) {
            Input input = new Input(node.id);
            for (NodePart part : node.parts) {
                MeshPart range = part.meshPart;
                if (!part.enabled || range.primitiveType != GL20.GL_TRIANGLES || range.size < 3 || uses.get(range) > 1
                      || range.mesh.getNumIndices() == 0) { continue; }
                VertexAttributes layout = range.mesh.getVertexAttributes();
                float[] all = vertices.computeIfAbsent(range.mesh,
                      mesh -> mesh.getVertices(new float[mesh.getNumVertices() * layout.vertexSize / Float.BYTES]));
                short[] order = orders.computeIfAbsent(range.mesh, mesh -> {
                    short[] read = new short[mesh.getNumIndices()];
                    mesh.getIndices(read);
                    return read;
                });
                if (!input.add(rangeKey(range), part.material.id, layout, all, order, range.offset, range.size)) {
                    input = null;
                    break;
                }
            }
            if (input != null && input.layout != null) { inputs.add(input); }
        }
        Set<String> glass = Set.copyOf(clear);
        Object key = grade(model, grade);
        return () -> {
            var result = new KitFragments();
            result.ranged = true;
            if (inputs.isEmpty()) { return result; }
            float cell = side * GRADE_CELLS[grade];
            Path file = stored(inputs, "shell lattice " + cell + ' ' + SHELL_FRAGMENTS + ' ' + glass + ' ');
            if (file != null && result.read(file)) {
                ON_DISK.put(key, Boolean.TRUE);
                return result;
            }
            for (Input input : inputs) { result.breakUp(input, cell, SHELL_FRAGMENTS, glass, true); }
            if (file != null) {
                result.write(file);
                ON_DISK.put(key, Boolean.TRUE);
            }
            return result;
        };
    }

    private static void nodes(Iterable<Node> nodes, List<Node> result) {
        for (Node node : nodes) {
            result.add(node);
            nodes(node.getChildren(), result);
        }
    }

    /** A shell's part by its range: a model built at runtime may name several parts alike. */
    private static String rangeKey(MeshPart range) { return range.id + '@' + range.offset + '+' + range.size; }

    /**
     * Runs a cut ({@link #shell}, a bridge's span) on a cutter thread, {@code failed} logged should it fail; cancelled
     * while it waits its turn, it never runs.
     */
    static <T> CompletableFuture<T> later(Supplier<T> work, String failed) {
        var cut = CompletableFuture.supplyAsync(work, CUTTER);
        cut.whenComplete((done, failure) -> {
            if (failure != null && !(failure instanceof java.util.concurrent.CancellationException)) {
                LOGGER.warn(failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                      ? failure.getCause() : failure, failed);
            }
        });
        return cut;
    }

    /**
     * The sources whose cuts the store holds (#onDisk): a kit node's Level.Source or a shell's grade key (#grade), noted
     * as a cut of theirs is read back or written, by the background fill or a fall. A fall named now waits for their
     * reads, which take milliseconds; for any other it never waits (TerrainFalls#reading).
     */
    private static final Map<Object, Boolean> ON_DISK = Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** Whether the store holds the source's cut, as far as this run has seen. */
    static boolean onDisk(Object source) { return ON_DISK.containsKey(source); }

    /** The shell models whose cuts were asked ahead for the store (#storeLater): each once while it lives. */
    private static final Set<Model> STORED_SHELLS = Collections.newSetFromMap(new java.util.WeakHashMap<>());
    /** One key a shell model and grade, what {@link Cuts} holds its cut by ({@link #grade}). */
    private static final Map<Model, Object[]> GRADES = new java.util.WeakHashMap<>();

    /** What {@link Cuts} holds the shell model's cut at {@code grade} by: the same object for the model's life. Render thread. */
    static Object grade(Model model, int grade) {
        return GRADES.computeIfAbsent(model, any -> new Object[] { new Object(), new Object() })[grade];
    }

    /**
     * Makes the shell model's cut ({@link #shell}) and keeps it, in the background, unless it was asked before: for the
     * structures a board draws up close, so that their falls read their cuts back. Render thread.
     */
    static void storeLater(Model model, float side, Set<String> clear) {
        if (store == null || !STORED_SHELLS.add(model)) { return; }
        // The coarse grade first: it is the one a fall takes at most distances, and the quicker to make.
        for (int grade : new int[] { COARSE, FINE }) {
            var shell = shell(model, side, clear, grade);
            FILLER.execute(() -> {
                try {
                    shell.get();
                } catch (RuntimeException failure) {
                    LOGGER.warn(failure, "A structure's cut could not be made ahead");
                }
            });
        }
    }

    private void add(ModelNode node, Level level) {
        Input input = new Input(node.id);
        for (ModelNodePart nodePart : node.parts) {
            ModelMeshPart part = level.partOf.get(nodePart.meshPartId);
            ModelMesh mesh = level.meshOf.get(nodePart.meshPartId);
            if (part == null || part.primitiveType != GL20.GL_TRIANGLES || part.indices.length < 3
                  || level.skipped.contains(nodePart.materialId)) { continue; }
            // No mesh part another node draws too.
            if (level.nodeOf.get(nodePart.meshPartId) != node
                  || !input.add(nodePart.meshPartId, nodePart.materialId, new VertexAttributes(mesh.attributes),
                        mesh.vertices, part.indices, 0, part.indices.length)) { return; }
        }
        if (input.layout == null) { return; }
        // Cut before, in this run or another: its cut is read back. Else it is cut now, and kept.
        Path file = stored(List.of(input), "");
        if (file != null && read(file)) { return; }
        breakUp(input, SIDE, KIT_FRAGMENTS, Set.of(), false);
        if (file != null) { write(file); }
    }

    /**
     * One node's parts as one cut reads them: each part's triangles, unindexed, in its mesh's frame, a channel of its own
     * (though two share a material); the layout they share, and how many vertices they hold whole.
     */
    private static final class Input {
        final String name;
        final List<Material> materials = new ArrayList<>();
        final List<FloatArray> triangles = new ArrayList<>();
        final List<String> ids = new ArrayList<>();
        VertexAttributes layout;
        int whole;

        Input(String name) { this.name = name; }

        /**
         * Adds a part's {@code count} indices from {@code offset} into {@code vertices}. False where the node cannot
         * break: a second layout, or colour slots, whose colours a placement carries.
         */
        boolean add(String id, String material, VertexAttributes attributes, float[] vertices, short[] indices,
              int offset, int count) {
            if (layout != null && !layout.equals(attributes) || RigidGlb.colourSlotted(attributes)) { return false; }
            layout = attributes;
            int stride = layout.vertexSize / Float.BYTES;
            var records = new FloatArray(count * stride);
            Set<Integer> used = new HashSet<>();
            for (int at = offset; at < offset + count; at++) {
                int vertex = Short.toUnsignedInt(indices[at]);
                records.addAll(vertices, vertex * stride, stride);
                used.add(vertex);
            }
            whole += used.size();
            materials.add(new Material(material));
            triangles.add(records);
            ids.add(id);
            return true;
        }
    }

    /** The node's parts as {@link #add} gathered them, broken into the fragments that fit the bounds, if any do. */
    private void breakUp(Input input, float side, int most, Set<String> clear, boolean lattice) {
        VertexAttributes layout = input.layout;
        List<FloatArray> triangles = input.triangles;
        List<Material> materials = input.materials;
        int whole = input.whole;
        int stride = layout.vertexSize / Float.BYTES, position = layout.findByUsage(VertexAttributes.Usage.Position).offset / Float.BYTES;
        var bounds = new BoundingBox().inf();
        var point = new Vector3();
        for (FloatArray records : triangles) {
            for (int at = 0; at < records.size; at += stride) {
                bounds.ext(point.set(records.items[at + position], records.items[at + position + 1], records.items[at + position + 2]));
            }
        }
        int steep = MathUtils.clamp(Math.round(Math.max(bounds.getWidth(), bounds.getHeight()) / side)
              + Math.round(bounds.getDepth() / (2 * side)), 0, 8);
        if (steep == 0) { return; }
        float[] walls = walls(triangles, stride, position);
        // Each part's own material object: two parts in one material weigh apart. Glass ({@code clear}) never closes a cut.
        Map<Material, Integer> sizes = new java.util.IdentityHashMap<>();
        for (int index = 0; index < materials.size(); index++) {
            sizes.put(materials.get(index), clear.contains(materials.get(index).id) ? -1 : triangles.get(index).size);
        }
        Material body = MeepleShatter.Caps.body(materials, sizes::get);
        // Closed where a wall is closed: an open sheet's cut stays open, never filled across the room. Textured a
        // storey to a repeat, in the node's own units.
        var caps = new MeepleShatter.Caps(body, layout, (float) KitOutline.LEVEL * side / SIDE, false);
        if (lattice) {
            // Cells about {@code side} each way, along its walls, across them and up, made larger until at most
            // {@code most} fit, and while a part outgrows a batch's vertices: no piece much longer than it is wide. The
            // grade asked sets their size, not a vertex bound: a fine grade is drawn only where its pieces show.
            float cell = side;
            while (cells(bounds, walls[0], cell) > most) { cell *= 1.1f; }
            // Until a part fits a batch: a share of tens of thousands of triangles needs a few larger tries, never whole.
            for (;; cell *= 1.25f) {
                var cut = MeepleShatter.cut(materials, triangles, layout, lattice(bounds, walls[0], cell, input.name.hashCode()), caps);
                if (cut.pieces() < 2) { return; }
                List<Packed> packed = cut.pieces() > most ? null : pack(Split.of(cut, body), layout);
                if (packed == null) { continue; }
                for (int index = 0; index < packed.size(); index++) { place(input.ids.get(index), layout, packed.get(index)); }
                return;
            }
        }
        // The most breaks that keep within the bounds. Each break more only adds pieces and vertices (its planes are the
        // first of the next count's), so the search halves its way there: a node of thousands of triangles is cut three
        // times, not once for every count down from the most.
        List<Packed> best = null;
        for (int fits = 0, over = steep + 1; fits + 1 < over; ) {
            int breaks = (fits + over) / 2;
            var cut = MeepleShatter.cut(materials, triangles, layout, Vector3.Z,
                  MeepleShatter.Planes.across(breaks, walls[0], walls[1] < ONE_WAY), input.name.hashCode(), caps);
            // Fewer than two pieces fit the bounds and break nothing.
            List<Packed> packed = cut.pieces() < 2 ? List.of()
                  : cut.pieces() > most ? null : pack(Split.of(cut, body), layout);
            if (packed != null && packed.stream().mapToInt(part -> part.vertices().size / stride).sum()
                  > Math.max(VERTEX_SHARE * whole, whole + SMALL_EXTRA)) { packed = null; }
            if (packed == null) {
                over = breaks;
            } else {
                fits = breaks;
                best = packed;
            }
        }
        if (best == null) { return; }
        for (int index = 0; index < best.size(); index++) { place(input.ids.get(index), layout, best.get(index)); }
    }

    /** How many cells about {@code cell} wide the bounds take, along {@code heading} (degrees), across it and up. */
    private static int cells(BoundingBox bounds, float heading, float cell) {
        float[] spans = spans(bounds, heading);
        return Math.max(1, Math.round(spans[1] / cell)) * Math.max(1, Math.round(spans[3] / cell))
              * Math.max(1, Math.round(bounds.getDepth() / cell));
    }

    /** The bounds' least reach and span along {@code heading} (degrees) about the vertical, then across it. */
    private static float[] spans(BoundingBox bounds, float heading) {
        float cos = MathUtils.cosDeg(heading), sin = MathUtils.sinDeg(heading);
        float[] result = { Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE };
        for (int corner = 0; corner < 4; corner++) {
            float x = (corner & 1) == 0 ? bounds.min.x : bounds.max.x, y = (corner & 2) == 0 ? bounds.min.y : bounds.max.y;
            float along = x * cos + y * sin, across = y * cos - x * sin;
            result[0] = Math.min(result[0], along);
            result[1] = Math.max(result[1], along);
            result[2] = Math.min(result[2], across);
            result[3] = Math.max(result[3], across);
        }
        result[1] -= result[0];
        result[3] -= result[2];
        return result;
    }

    /**
     * The breaks of a lattice of cells about {@code cell} wide: upright ones along {@code heading} (degrees) and across
     * it, level ones up the height, each spread evenly and set off and tilted a little, so that no break lies in a wall's
     * own plane and the pieces look broken rather than sawn. Each is a normal and its offset.
     */
    private static List<float[]> lattice(BoundingBox bounds, float heading, float cell, int seed) {
        float[] spans = spans(bounds, heading);
        List<float[]> result = new ArrayList<>();
        int along = Math.max(1, Math.round(spans[1] / cell)), across = Math.max(1, Math.round(spans[3] / cell));
        int up = Math.max(1, Math.round(bounds.getDepth() / cell));
        float[][] axes = { { heading, spans[0], spans[1], along }, { heading + 90, spans[2], spans[3], across } };
        int index = 0;
        for (float[] axis : axes) {
            for (int at = 1; at < axis[3]; at++, index++) {
                float turn = axis[0] + 16 * (UnitAttack.noise(seed + 31 * index) - .5f);
                float tilt = 8 * (UnitAttack.noise(seed + 31 * index + 7) - .5f);
                float level = MathUtils.cosDeg(tilt);
                float x = MathUtils.cosDeg(turn) * level, y = MathUtils.sinDeg(turn) * level, z = MathUtils.sinDeg(tilt);
                // Its share of the span, set off by up to a fifth of a cell, on the lattice's own axis.
                float share = (at + .4f * (UnitAttack.noise(seed + 31 * index + 13) - .5f)) / axis[3];
                float reach = axis[1] + share * axis[2];
                float px = reach * MathUtils.cosDeg(axis[0]), py = reach * MathUtils.sinDeg(axis[0]), pz = bounds.getCenterZ();
                result.add(new float[] { x, y, z, x * px + y * py + z * pz });
            }
        }
        for (int at = 1; at < up; at++, index++) {
            float turn = 360 * UnitAttack.noise(seed + 31 * index), tilt = 10 * UnitAttack.noise(seed + 31 * index + 7);
            float x = MathUtils.sinDeg(tilt) * MathUtils.cosDeg(turn), y = MathUtils.sinDeg(tilt) * MathUtils.sinDeg(turn);
            float z = MathUtils.cosDeg(tilt);
            float height = bounds.min.z + bounds.getDepth() * (at + .4f * (UnitAttack.noise(seed + 31 * index + 13) - .5f)) / up;
            result.add(new float[] { x, y, z, x * bounds.getCenterX() + y * bounds.getCenterY() + z * height });
        }
        return result;
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
     * Fragments by part: {@code triangles[fragment][part]} vertex records, and per fragment the caps that close it
     * ({@code caps}, null for none), drawn in the part {@code capped}.
     */
    private record Split(FloatArray[][] triangles, FloatArray[] caps, int capped) {
        int fragments() { return triangles.length; }

        int parts() { return triangles.length == 0 ? 0 : triangles[0].length; }

        /** A cut's pieces, its caps drawn in the body's part. */
        static Split of(MeepleShatter.Cut cut, Material body) {
            int parts = cut.channels() - 1, capped = -1;
            var triangles = new FloatArray[cut.pieces()][parts];
            var caps = new FloatArray[cut.pieces()];
            for (int part = 0; part < parts; part++) {
                if (cut.material(part) == body) { capped = part; }
            }
            for (int piece = 0; piece < cut.pieces(); piece++) {
                for (int part = 0; part < parts; part++) { triangles[piece][part] = cut.triangles(piece, part); }
                caps[piece] = cut.triangles(piece, parts);
            }
            return new Split(triangles, caps, capped);
        }
    }

    /**
     * Per part, its fragments one after another, each welded on its own so no two share a vertex, the caps in their part
     * after its own triangles; per fragment, a table entry of its box over every part and cap: centre and first vertex,
     * half size and the box's turn about the vertical, in radians. The box lies along the fragment's own walls (a
     * quarter turn at most), so that its thinnest side is the fragment's: a wall's piece comes to rest on its face
     * (tree-instances.glsl), whichever way its wall ran. Null when a part outgrows a batch's vertices.
     */
    private static List<Packed> pack(Split split, VertexAttributes layout) {
        int stride = layout.vertexSize / Float.BYTES, position = layout.findByUsage(VertexAttributes.Usage.Position).offset / Float.BYTES;
        int pieces = split.fragments();
        var boxes = new BoundingBox[pieces];
        float[] turns = new float[pieces];
        var point = new Vector3();
        for (int piece = 0; piece < pieces; piece++) {
            // The way its own faces face, not its caps': no turn for a wall along the kit's axes.
            float facing = walls(java.util.Arrays.asList(split.triangles()[piece]), stride, position)[0];
            turns[piece] = (facing - 90 * Math.round(facing / 90)) * MathUtils.degreesToRadians;
            float cos = (float) Math.cos(turns[piece]), sin = (float) Math.sin(turns[piece]);
            boxes[piece] = new BoundingBox().inf();
            for (int part = 0; part <= split.parts(); part++) {
                FloatArray records = part < split.parts() ? split.triangles()[piece][part]
                      : split.caps() == null ? new FloatArray(0) : split.caps()[piece];
                for (int at = 0; at < records.size; at += stride) {
                    float x = records.items[at + position], y = records.items[at + position + 1];
                    boxes[piece].ext(point.set(x * cos + y * sin, y * cos - x * sin, records.items[at + position + 2]));
                }
            }
        }
        List<Packed> result = new ArrayList<>();
        var centre = new Vector3();
        var half = new Vector3();
        for (int channel = 0; channel < split.parts(); channel++) {
            var vertices = new FloatArray();
            var indices = new ShortArray();
            float[] table = new float[8 * pieces];
            for (int piece = 0; piece < pieces; piece++) {
                boxes[piece].getCenter(centre);
                boxes[piece].getDimensions(half).scl(.5f);
                float cos = (float) Math.cos(turns[piece]), sin = (float) Math.sin(turns[piece]);
                int first = vertices.size / stride;
                table[8 * piece] = centre.x * cos - centre.y * sin;
                table[8 * piece + 1] = centre.x * sin + centre.y * cos;
                table[8 * piece + 2] = centre.z;
                table[8 * piece + 3] = first;
                table[8 * piece + 4] = half.x;
                table[8 * piece + 5] = half.y;
                table[8 * piece + 6] = half.z;
                table[8 * piece + 7] = turns[piece];
                Map<GeneratedMesh.Weld, Integer> welded = new HashMap<>();
                FloatArray own = split.triangles()[piece][channel];
                for (int at = 0; at < own.size; at += stride) { indices.add(GeneratedMesh.weld(own.items, at, stride, vertices, welded)); }
                if (channel == split.capped() && split.caps() != null) {
                    FloatArray closing = split.caps()[piece];
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

    /**
     * A tree's model in fragments for an attack that breaks it (TerrainFalls#breakTrees): its pieces grouped about up to
     * {@link #TREE_FRAGMENTS} middles, no cut and no cap, a fragment holding every part it reaches (bark and leaves). A
     * small connected piece (a leaf card, a twig) stays whole; a large one (a trunk with its boughs) breaks between its
     * triangles. Copied here from the model's meshes (the render thread's), grouped on a cutter thread. Null
     * where it stays whole: colour slots, or parts of two layouts.
     */
    static CompletableFuture<KitFragments> splitLater(Model model) {
        List<NodePart> drawn = new ArrayList<>();
        for (int index = 0; index < model.nodes.size; index++) { collect(model.nodes.get(index), drawn); }
        Map<String, Integer> uses = new HashMap<>();
        for (NodePart part : drawn) { uses.merge(part.meshPart.id, 1, Integer::sum); }
        Map<Mesh, float[]> vertices = new java.util.IdentityHashMap<>();
        Map<Mesh, short[]> orders = new java.util.IdentityHashMap<>();
        List<String> ids = new ArrayList<>();
        List<FloatArray> triangles = new ArrayList<>();
        List<int[]> pieces = new ArrayList<>();
        VertexAttributes layout = null;
        for (NodePart part : drawn) {
            MeshPart range = part.meshPart;
            if (!part.enabled || range.primitiveType != GL20.GL_TRIANGLES || range.size < 3 || uses.get(range.id) > 1) {
                continue;
            }
            var attributes = range.mesh.getVertexAttributes();
            if (layout != null && !layout.equals(attributes) || RigidGlb.colourSlotted(attributes)) {
                return CompletableFuture.completedFuture(null);
            }
            layout = attributes;
            int stride = layout.vertexSize / Float.BYTES;
            float[] all = vertices.computeIfAbsent(range.mesh, mesh -> mesh.getVertices(new float[mesh.getNumVertices() * stride]));
            short[] order = orders.computeIfAbsent(range.mesh, mesh -> {
                short[] read = new short[mesh.getNumIndices()];
                if (read.length > 0) { mesh.getIndices(read); }
                return read;
            });
            var records = new FloatArray(range.size * stride);
            // Its triangles' connected pieces: a triangle joins the piece of each vertex it shares.
            int count = range.size / 3;
            int[] piece = new int[count];
            Map<Integer, Integer> joined = new HashMap<>();
            for (int triangle = 0; triangle < count; triangle++) {
                piece[triangle] = triangle;
                for (int corner = 0; corner < 3; corner++) {
                    int index = range.offset + 3 * triangle + corner;
                    int vertex = order.length == 0 ? index : Short.toUnsignedInt(order[index]);
                    records.addAll(all, vertex * stride, stride);
                    Integer other = joined.putIfAbsent(vertex, triangle);
                    if (other != null) { join(piece, other, triangle); }
                }
            }
            for (int triangle = 0; triangle < count; triangle++) { piece[triangle] = root(piece, triangle); }
            ids.add(range.id);
            triangles.add(records);
            pieces.add(piece);
        }
        if (layout == null) { return CompletableFuture.completedFuture(null); }
        VertexAttributes shared = layout;
        return CompletableFuture.supplyAsync(() -> split(ids, triangles, pieces, shared), CUTTER);
    }

    private static int root(int[] piece, int triangle) {
        while (piece[triangle] != triangle) {
            piece[triangle] = piece[piece[triangle]];
            triangle = piece[triangle];
        }
        return triangle;
    }

    private static void join(int[] piece, int a, int b) {
        int first = root(piece, a), second = root(piece, b);
        if (first != second) { piece[Math.max(first, second)] = Math.min(first, second); }
    }

    /** Index loops: libGDX's Array reuses its iterators. */
    private static void collect(Node node, List<NodePart> result) {
        for (int index = 0; index < node.parts.size; index++) { result.add(node.parts.get(index)); }
        for (int index = 0; index < node.getChildCount(); index++) { collect(node.getChild(index), result); }
    }

    /**
     * Groups the parts' pieces ({@code pieces}: each triangle's connected piece in its part) about their fragments'
     * middles, each whole in the nearest; a piece of over a fragment's share of the triangles by its triangles.
     */
    private static KitFragments split(List<String> ids, List<FloatArray> triangles, List<int[]> pieces,
          VertexAttributes layout) {
        int stride = layout.vertexSize / Float.BYTES, position = layout.findByUsage(VertexAttributes.Usage.Position).offset / Float.BYTES;
        int total = triangles.stream().mapToInt(records -> records.size / (3 * stride)).sum();
        var result = new KitFragments();
        int count = Math.min(TREE_FRAGMENTS, total / TREE_TRIANGLES);
        if (count < 2) { return result; }
        // What moves as one: a small piece whole, a large one's triangles each. Its middle: its triangles' mean.
        int share = Math.max(2, total / (2 * count));
        int[] unitOf = new int[total];
        List<float[]> sums = new ArrayList<>();
        int at = 0;
        for (int part = 0; part < triangles.size(); part++) {
            FloatArray records = triangles.get(part);
            int[] piece = pieces.get(part);
            Map<Integer, Integer> sizes = new HashMap<>();
            for (int root : piece) { sizes.merge(root, 1, Integer::sum); }
            Map<Integer, Integer> units = new HashMap<>();
            for (int triangle = 0; triangle < piece.length; triangle++, at++) {
                int unit = sizes.get(piece[triangle]) > share ? -1 : units.getOrDefault(piece[triangle], -1);
                if (unit < 0) {
                    unit = sums.size();
                    sums.add(new float[4]);
                    if (sizes.get(piece[triangle]) <= share) { units.put(piece[triangle], unit); }
                }
                unitOf[at] = unit;
                int first = 3 * stride * triangle;
                for (int axis = 0; axis < 3; axis++) {
                    sums.get(unit)[axis] += (records.items[first + position + axis] + records.items[first + stride + position + axis]
                          + records.items[first + 2 * stride + position + axis]) / 3;
                }
                sums.get(unit)[3]++;
            }
        }
        int units = sums.size();
        if (units < 2) { return result; }
        count = Math.min(count, units);
        float[] middles = new float[3 * units];
        for (int unit = 0; unit < units; unit++) {
            for (int axis = 0; axis < 3; axis++) { middles[3 * unit + axis] = sums.get(unit)[axis] / sums.get(unit)[3]; }
        }
        // Spread first: each next middle the unit farthest from those chosen, from the first unit's.
        float[] centres = new float[3 * count];
        float[] nearest = new float[units];
        java.util.Arrays.fill(nearest, Float.MAX_VALUE);
        int chosen = 0;
        for (int fragment = 0; fragment < count; fragment++) {
            System.arraycopy(middles, 3 * chosen, centres, 3 * fragment, 3);
            int farthest = 0;
            for (int unit = 0; unit < units; unit++) {
                nearest[unit] = Math.min(nearest[unit], distance2(middles, unit, centres, fragment));
                if (nearest[unit] > nearest[farthest]) { farthest = unit; }
            }
            chosen = farthest;
        }
        int[] owner = new int[units];
        for (int round = 0; round < TREE_ROUNDS; round++) {
            float[] centred = new float[4 * count];
            for (int unit = 0; unit < units; unit++) {
                int best = 0;
                for (int fragment = 1; fragment < count; fragment++) {
                    if (distance2(middles, unit, centres, fragment) < distance2(middles, unit, centres, best)) {
                        best = fragment;
                    }
                }
                owner[unit] = best;
                for (int axis = 0; axis < 3; axis++) { centred[4 * best + axis] += middles[3 * unit + axis]; }
                centred[4 * best + 3]++;
            }
            for (int fragment = 0; fragment < count; fragment++) {
                if (centred[4 * fragment + 3] == 0) { continue; }
                for (int axis = 0; axis < 3; axis++) {
                    centres[3 * fragment + axis] = centred[4 * fragment + axis] / centred[4 * fragment + 3];
                }
            }
        }
        // The fragments that kept triangles, in order, each part's triangles in its own.
        int[] kept = new int[count];
        java.util.Arrays.fill(kept, -1);
        int fragments = 0;
        for (int unit = 0; unit < units; unit++) {
            if (kept[owner[unit]] < 0) { kept[owner[unit]] = fragments++; }
        }
        if (fragments < 2) { return result; }
        var grouped = new FloatArray[fragments][triangles.size()];
        for (FloatArray[] parts : grouped) {
            for (int part = 0; part < parts.length; part++) { parts[part] = new FloatArray(); }
        }
        at = 0;
        for (int part = 0; part < triangles.size(); part++) {
            FloatArray records = triangles.get(part);
            for (int first = 0; first + 3 * stride <= records.size; first += 3 * stride) {
                grouped[kept[owner[unitOf[at++]]]][part].addAll(records.items, first, 3 * stride);
            }
        }
        var packed = pack(new Split(grouped, null, -1), layout);
        if (packed == null) { return result; }
        for (int index = 0; index < packed.size(); index++) { result.place(ids.get(index), layout, packed.get(index)); }
        return result;
    }

    private static float distance2(float[] middles, int unit, float[] centres, int fragment) {
        float x = middles[3 * unit] - centres[3 * fragment], y = middles[3 * unit + 1] - centres[3 * fragment + 1];
        float z = middles[3 * unit + 2] - centres[3 * fragment + 2];
        return x * x + y * y + z * z;
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

    /** Render thread, once: CPU-only meshes (no GL object) the fall lists copy their parts' ranges from. */
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

    /** The drawn part's fragments, by its mesh part's id; null where it falls whole. */
    Part part(MeshPart drawn) { return parts.get(ranged ? rangeKey(drawn) : drawn.id); }

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

    /**
     * The cuts of the falls announced or under way, and those the last falls left within a budget: the one record of
     * which fragments exist. A kit node or a tree's model is cut once, however many hexes ask ({@link #want}), in the
     * order asked, and held while a hex that asked still awaits its fall or lies fallen ({@link #retain}). One no hex
     * holds is kept for the next fall while the cuts' bytes stay within the budget, the least recently used going first.
     * Nothing is cut at a board's load or at the moment of a fall. Render thread.
     */
    static final class Cuts {
        /**
         * The most bytes the cuts take: their meshes' native bytes, each counted twice, as a fall draws a copy of them in
         * the pools (GpuIndirectInstances) for as long as it lasts. Past it the cuts no hex holds are freed, the
         * least recently used first; where those held alone fill it, a new cut is dropped and its part falls whole.
         */
        static final long BYTES = 96L << 20;
        /** The most mesh bytes a frame installs (#install). */
        static final long INSTALL_BYTES = 16L << 20;

        /** One source's cut and the hexes that hold it: those awaiting their fall with it, and those fallen in it. */
        private static final class Cut {
            final Object source;
            final CompletableFuture<KitFragments> made;
            final Set<Coords> hexes = new HashSet<>();
            /** Installed, within the budget: its bytes against it, and when its last hex let go (a count of such moments). */
            boolean installed;
            long bytes, idle;

            Cut(Object source, CompletableFuture<KitFragments> made) {
                this.source = source;
                this.made = made;
            }

            /** Its fragments once cut; null while it is cut, or where it stays whole (a failed cut, a tree of two layouts). */
            KitFragments fragments() { return made.isDone() && !made.isCompletedExceptionally() ? made.join() : null; }
        }

        /** By what is cut: a kit level's node (its Level.Source) or a tree's Model, one object for all that draw it. */
        private final Map<Object, Cut> cuts = new IdentityHashMap<>();
        /** Those still under way, or finished and not installed yet. */
        private final List<Cut> making = new ArrayList<>();
        /** The hexes whose fall is announced and has not begun, and those of them whose cuts were not asked yet. */
        private final Set<Coords> awaited = new HashSet<>(), unasked = new HashSet<>();
        private final long budget;
        private long held, clock;
        private int late;

        Cuts() { this(BYTES); }

        /** With another budget than {@link #BYTES}: for tests. */
        Cuts(long budget) { this.budget = budget; }

        /**
         * The hex's fall is announced. Its structures' cuts are asked once they show large enough for their fragments to
         * be seen ({@link #unasked}, {@link #ask}); until then, and if never, they fall whole. Whether it is news.
         */
        boolean expect(Coords hex) {
            return awaited.add(hex) && unasked.add(hex);
        }

        /** The awaited hexes whose cuts were not asked yet: too small on screen so far, or out of view. */
        List<Coords> unasked() { return unasked.isEmpty() ? List.of() : List.copyOf(unasked); }

        /** The hex's cuts are asked now ({@link #want}): it shows large enough. */
        void ask(Coords hex) { unasked.remove(hex); }

        /** {@code hex}'s fall to come needs {@code source} in fragments: {@code cut} starts its cut, unless one exists. */
        void want(Object source, Coords hex, Supplier<CompletableFuture<KitFragments>> cut) {
            awaited.add(hex);
            Cut wanted = cuts.get(source);
            if (wanted == null) {
                wanted = new Cut(source, cut.get());
                cuts.put(source, wanted);
                making.add(wanted);
            }
            wanted.hexes.add(hex);
        }

        /** {@code hex}'s fall to come needs the kit nodes of {@code level} that {@code drawn} and their children stand for. */
        void want(Level level, Iterable<Node> drawn, Coords hex) {
            for (Node node : drawn) {
                Level.Source source = level.source(node);
                if (source != null) { want(source, hex, () -> cutLater(source)); }
                want(level, node.getChildren(), hex);
            }
        }

        /** Whether the hex's fall is announced and has not begun. */
        boolean awaits(Coords hex) { return awaited.contains(hex); }

        /** Whether a cut that one of {@code hexes} asked for is still under way, or finished and not installed yet. */
        boolean pending(Collection<Coords> hexes) {
            for (Cut cut : making) {
                if (!Collections.disjoint(cut.hexes, hexes)) { return true; }
            }
            return false;
        }

        /** Whether any cut is still under way or to install: for tests. */
        boolean pending() { return !making.isEmpty(); }

        /**
         * Whether one of {@code hexes} still waits for a cut the store holds (#onDisk): queued, being read or read and
         * not installed. A cut that has to be made never counts: its part falls in what is in.
         */
        boolean reading(Collection<Coords> hexes) {
            for (Cut cut : making) {
                if (!Collections.disjoint(cut.hexes, hexes) && onDisk(cut.source)) { return true; }
            }
            return false;
        }

        /**
         * Installs the cuts that finished, each frame, never in the frame of the fall they are for. Returns the awaited
         * hexes whose cuts came in: their fragments' lists are to be written now (GpuDestruction#prepare).
         */
        Set<Coords> install() {
            if (making.isEmpty()) { return Set.of(); }
            Set<Coords> ready = new HashSet<>();
            // A frame installs at most INSTALL_BYTES of meshes (their GL copies are made here): a strike's reads spread
            // over a few frames instead of one.
            long[] installed = { 0 };
            making.removeIf(cut -> {
                if (!cut.made.isDone() || installed[0] >= INSTALL_BYTES) { return false; }
                KitFragments done = cut.fragments();
                installed[0] += done == null ? 0 : done.bytes();
                if (admit(cut)) {
                    for (Coords hex : cut.hexes) {
                        if (awaited.contains(hex)) { ready.add(hex); }
                    }
                }
                return true;
            });
            return ready;
        }

        /** Installs the finished cut's fragments if it has any and the budget has room for them, and says whether it did. */
        private boolean admit(Cut cut) {
            KitFragments done = cut.fragments();
            if (done == null) { return false; }
            done.install();
            cut.bytes = 2 * done.bytes();
            // Room for it: the cuts no hex holds go, the longest unused first.
            while (held + cut.bytes > budget) {
                Cut idle = null;
                for (Cut other : cuts.values()) {
                    if (other.installed && other.hexes.isEmpty() && (idle == null || other.idle < idle.idle)) { idle = other; }
                }
                if (idle == null) { break; }
                free(idle);
            }
            if (held + cut.bytes > budget) {
                // The falls under way and to come fill the budget: this part falls whole.
                done.dispose();
                cuts.remove(cut.source);
                return false;
            }
            cut.installed = true;
            held += cut.bytes;
            return true;
        }

        private void free(Cut cut) {
            held -= cut.bytes;
            cut.fragments().dispose();
            cuts.remove(cut.source);
        }

        /**
         * {@code source}'s fragments for {@code hex}'s fall, which holds them until its pile has sunk: those installed
         * for it. Null where it falls whole: it did not ask for the cut, the source stays whole, the budget had no room,
         * or the cut is not in yet. With {@code begins} its fall begins now, and a cut not in counts as late; else this
         * is what the fall would find now, for its list (GpuDestruction#prepare).
         */
        KitFragments take(Object source, Coords hex, boolean begins) {
            Cut cut = source == null ? null : cuts.get(source);
            if (cut == null || !cut.hexes.contains(hex)) { return null; }
            if (begins && !cut.installed && making.contains(cut)) { late++; }
            return cut.installed ? cut.fragments() : null;
        }

        /**
         * {@code hex}'s fall begins with pieces made before (its prepared ones): each cut it asked for that is still
         * under way counts as late, as {@link #take} counts a node's at a fall that makes its pieces then.
         */
        void begins(Coords hex) {
            for (Cut cut : making) {
                if (!cut.installed && cut.hexes.contains(hex)) { late++; }
            }
        }

        /** {@code hex}'s fall has begun with the cuts that were in ({@link #take}): it awaits none of the others any more. */
        void fell(Coords hex) {
            awaited.remove(hex);
            unasked.remove(hex);
            release(cut -> {
                if (!cut.installed) { cut.hexes.remove(hex); }
            });
        }

        /** Lets go of the cuts for every hex that neither awaits its fall nor lies fallen ({@code fallen}) any more. */
        void retain(Set<Coords> fallen) {
            release(cut -> cut.hexes.removeIf(hex -> !awaited.contains(hex) && !fallen.contains(hex)));
        }

        /** No fall is awaited any more, the playback having played out: only {@code fallen} hexes hold their cuts. */
        void forget(Set<Coords> fallen) {
            awaited.clear();
            unasked.clear();
            retain(fallen);
        }

        /** Nothing falls or is about to (Instant playback, another board): no hex holds a cut. */
        void clear() {
            awaited.clear();
            unasked.clear();
            release(cut -> cut.hexes.clear());
        }

        /** Frees every cut, those kept for the next fall too. */
        void dispose() {
            clear();
            for (Cut cut : List.copyOf(cuts.values())) { free(cut); }
        }

        /**
         * After {@code change}: a cut no hex holds any more stays for the next fall if it is installed (the budget frees
         * it, {@link #admit}); else it goes: queued, it never runs; under way, it ends unused.
         */
        private void release(Consumer<Cut> change) {
            cuts.values().removeIf(cut -> {
                boolean was = !cut.hexes.isEmpty();
                change.accept(cut);
                if (!cut.hexes.isEmpty()) { return false; }
                if (cut.installed) {
                    if (was) { cut.idle = ++clock; }
                    return false;
                }
                cut.made.cancel(false);
                return true;
            });
            making.removeIf(cut -> cut.hexes.isEmpty());
        }

        /** How many sources are cut or being cut, those kept for the next fall included. */
        int size() { return cuts.size(); }

        /** The native bytes of the fragments' meshes that exist; the budget counts them twice ({@link #BYTES}). */
        long bytes() { return held / 2; }

        /** How many nodes and trees fell whole because the cut they asked for was still under way: for tests and reports. */
        int late() { return late; }
    }
}
