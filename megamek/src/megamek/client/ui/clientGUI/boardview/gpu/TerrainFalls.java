/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.model.NodePart;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.Array;
import megamek.client.ui.clientGUI.boardview.gpu.GpuTerrain.Chunk;
import megamek.client.ui.clientGUI.boardview.gpu.GpuTerrain.Layer;
import megamek.client.ui.clientGUI.boardview.gpu.GpuTerrain.Prop;
import megamek.client.ui.clientGUI.boardview.gpu.GpuTerrain.TileMesh;
import megamek.client.ui.clientGUI.boardview.gpu.GpuTerrain.TileRange;
import megamek.client.ui.clientGUI.boardview.gpu.GpuTerrainLayer.MeshBatch;
import megamek.common.board.Coords;

/**
 * What the playback brings down on a terrain: the structures and woods a collapse, a strike or an attack names leave
 * their chunks for their pieces. Structures (kits, authored buildings, domes, walls, fuel tanks) and trees fall on the
 * GPU ({@link GpuDestruction}): their cuts are asked as the timeline names a fall ({@link #prepareFalls}), their lists
 * written ahead ({@link #prepareLists}), their hexes taken as the fall begins ({@link #presentCollapses},
 * {@link #strike}). A bridge's span, part of its chunk's terrain meshes, is read from its chunk and cut as its fall is
 * named ({@link #span}), and falls through the same engine as nodes of a model of its own once the terrain shows it gone
 * ({@link #startSpans}).
 * <p>
 * The terrain keeps the chunks. This class reads a hex's props and ranges where its chunk draws them
 * ({@link GpuTerrain#chunkOf}), has a chunk snapshot its props again without what fell ({@link GpuTerrain#refall},
 * {@link GpuTerrain#stampFalls}, {@link GpuTerrain#refreshShadows}), and asks the installed scene what still stands
 * ({@link GpuTerrain#coverScene}). The terrain draws the pieces and tells this class when it installs a scene, a chunk
 * or a level of detail. Render thread only; presentation, never game state.
 */
final class TerrainFalls {
    private final GpuTerrain terrain;
    private final GpuDestruction destruction;
    private final GpuAssets assets;

    TerrainFalls(GpuTerrain terrain, GpuDestruction destruction, GpuAssets assets) {
        this.terrain = terrain;
        this.destruction = destruction;
        this.assets = assets;
    }

    /**
     * Whether the structures {@code transition} brings down are ready to be taken: none still waits for the read of a
     * cut the store holds or for its list (#reading), and no bridge's span still stands in its chunk, waiting for the
     * terrain to show it gone (#startSpans): a unit on it falls on the transition's clock, with its pieces. Bounded by
     * what is read and by the terrain's commit, never by a clock.
     */
    boolean ready(BoardScene.TileTransition transition) {
        if (transition.collapses().isEmpty() || terrain.coverScene == null || terrain.columns()) { return true; }
        if (fallsFor != transition && reading(transition.collapses())) { return false; }
        return transition.collapses().stream().map(BoardScene.TileTransition.Collapse::coords)
              .noneMatch(at -> spansFalling.containsKey(at) || !destruction.taken(at) && spanTile(at) != null);
    }


    /**
     * Whether a structure of these collapses waits: a cut of its that the store holds is still to be read and installed,
     * or the list of its pieces is still to be written. A cut that has to be made is never waited for: a part without
     * one falls whole, and the background fill keeps it for the next fall.
     */
    private boolean reading(List<BoardScene.TileTransition.Collapse> collapses) {
        Set<Coords> hexes = new HashSet<>();
        for (var collapse : collapses) { hexes.add(collapse.coords()); }
        return cuts.reading(hexes) || hexes.stream().anyMatch(at -> cuts.awaits(at)
              && (fallsPreparing.contains(at) || destruction.building(at)));
    }

    /** The transition playing waits for its reads before it takes its structures (#reading). */
    private boolean fallsWaiting;

    /** Whether the prop lies or rides among the falling pieces, or waits to rise: its chunk leaves it out of this pass. */
    boolean hides(Prop prop, boolean shadows) {
        // A roof's objects stand while their structure waits for its pieces (GpuDestruction#shown), and cast their
        // standing shadow until those pieces cast (GpuDestruction#casting).
        if (!carried.isEmpty() && carried.contains(prop)
              && (shadows ? destruction.casting(prop.coords) : destruction.shown(prop.coords))) { return true; }
        if (!broken.isEmpty() && broken.contains(prop) && destruction.felled(prop.instance().transform)) { return true; }
        return !shadows && !rising.isEmpty() && rising.contains(prop) && destruction.taken(prop.coords);
    }

    /**
     * The cuts of the structures and woods about to fall or fallen, asked as the playback's timeline names a fall
     * (#prepareFalls), and of the last falls within a budget (KitFragments.Cuts): none is made at load or for the board.
     */
    final KitFragments.Cuts cuts = new KitFragments.Cuts();
    /** Whether the playback had anything left to play at the last frame (#prepareFalls). */
    private boolean fallsPlaying;

    /**
     * Rubble the rules left where a structure fell (Prop#rubble), which its chunk does not draw while its hex's fall
     * lasts: it rises out of the ground under the fall's dust (#takeRubble). Its shadow stays in the cached map, under
     * the pile.
     */
    private final Set<Prop> rising = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    /** Objects that stood on a falling structure (a helipad, roof fittings): they fall with it, as its pieces. */
    private final Set<Prop> carried = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    /** Woods cover that draws on its own (crystals, a skin's rows), broken into flying pieces while they last (#breakGrove). */
    private final Set<Prop> broken = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Takes the structures {@code transition} brings down (#presentFalls); a bridge's span waits for the terrain to show
     * it gone (#startSpans), its cut asked now where its fall was not named before.
     */
    void presentCollapses(BoardScene.TileTransition transition, float progress) {
        presentFalls(transition, progress);
        if (transition == null || transition.collapses().isEmpty() || terrain.coverScene == null || fallsWaiting) { return; }
        terrain.settings().run(() -> {
            for (var collapse : transition.collapses()) {
                Coords at = collapse.coords();
                if (spansFalling.containsKey(at) || destruction.taken(at) || !spans.containsKey(at) && !prepareSpan(at)) {
                    continue;
                }
                spansFalling.put(at, collapse);
            }
        });
    }

    /** The transition whose kit structures were taken for their pieces, and whether they have begun to give way. */
    private BoardScene.TileTransition fallsFor;
    private boolean fallsStarted;
    /** A building's hexes give way one after another, this many playback seconds a hex from where it was hit. */
    private static final float FALL_CHAIN_SECONDS = .06f, FALL_CHAIN_LIMIT = 1.5f;

    /**
     * Takes the kit structures {@code transition} brings down from their chunks as it begins, in the cuts that are in
     * and whose parts are placed then, never waiting for one (#fallPieces): their pieces stand where they stood
     * (GpuDestruction) and give way once its clock runs, after the camera has framed them
     * ({@code progress} above 0), hex after hex from the one hit. A transition that ends unframed lets them go at once.
     * Classic columns snap.
     */
    private void presentFalls(BoardScene.TileTransition transition, float progress) {
        fallsWaiting = false;
        if (fallsFor != null && fallsFor != transition) {
            fallsFor.collapses().forEach(collapse -> destruction.start(collapse.coords(), 0));
            fallsFor = null;
        }
        if (transition == null || transition.collapses().isEmpty() || terrain.coverScene == null || terrain.columns()) {
            return;
        }
        if (fallsFor == null) {
            // Its structures stand as they stood while their stored cuts are read.
            fallsWaiting = reading(transition.collapses());
            if (fallsWaiting) { return; }
            fallsFor = transition;
            fallsStarted = false;
            Set<Chunk> taken = new LinkedHashSet<>();
            terrain.settings().run(() -> {
                for (var collapse : transition.collapses()) {
                    // A strike's front took it already: its pieces fall or lie there.
                    Chunk chunk = destruction.taken(collapse.coords()) ? null : takeFall(collapse, 0);
                    if (chunk != null) { taken.add(chunk); }
                }
            });
            if (!taken.isEmpty()) {
                taken.forEach(terrain::fallen);
                // The structures leave the cached shadow too, where they stood: their pieces cast over it.
                terrain.refreshShadows(destruction.takenSince());
            }
            terrain.stampFalls();
        }
        if (progress > 0 && !fallsStarted) {
            fallsStarted = true;
            // From where a unit's weight brought it down, else from the hardest hit.
            var first = transition.collapses().stream().max(Comparator.comparing(
                  (BoardScene.TileTransition.Collapse collapse) -> !collapse.loads().isEmpty())
                  .thenComparingDouble(BoardScene.TileTransition.Collapse::strength)).orElseThrow().coords();
            for (var collapse : transition.collapses()) {
                destruction.start(collapse.coords(), Math.min(FALL_CHAIN_LIMIT, FALL_CHAIN_SECONDS * first.distance(collapse.coords())));
            }
        }
    }

    /**
     * Takes the kit structures standing at the collapse's hex for their pieces (#fallPieces). The chunk that drew them,
     * or null where none stands.
     */
    private Chunk takeFall(BoardScene.TileTransition.Collapse change, float blast) {
        Coords at = change.coords();
        Chunk chunk = terrain.chunkOf(at);
        if (chunk == null) { return null; }
        TileMesh mesh = chunk.tileMeshes.get(at);
        if (mesh == null) { return null; }
        // The pieces prepared for it (#prepareLists) where its mesh and its cuts are as they were then: the take makes
        // none and finds its list written. Else, where its chunk hides it by key, they are made in the frames to come
        // (#supplyFalls), before its first piece moves, and it stands in its chunk meanwhile; else they are made now.
        Array<Renderable> pieces = fallsPreparing.contains(at) ? null : destruction.preparedPieces(at, mesh);
        // By identity: an assembly's own hash reads its every placement, and a city's take met each thousands of times.
        Set<GpuBuilding.Assembly> buildings = assemblies();
        BoundingBox bounds = new BoundingBox().inf();
        if (pieces != null && !pieces.isEmpty()) {
            fallPieces(mesh, at, true, null, buildings, bounds);
            cuts.begins(at);
        } else {
            fallPieces(mesh, at, true, null, buildings, bounds);
            if (!bounds.isValid()) { return null; }
            if (byReference(chunk, mesh, bounds)) {
                pieces = null;
                cuts.begins(at);
            } else {
                pieces = new Array<>();
                fallPieces(mesh, at, true, pieces, assemblies(), new BoundingBox().inf());
            }
        }
        // Its fall begins with the cuts that are in: it waits for no other.
        cuts.fell(at);
        if (pieces != null && pieces.isEmpty()) { return null; }
        var middle = BoardGeometry.center(at, change.before().elevation());
        var push = new Vector2();
        int flags = change.loads().isEmpty() ? 0 : GpuDestruction.LOADED;
        if (change.from() != null && change.strength() > 0) {
            var from = BoardGeometry.center(change.from().coords(), change.from().elevation());
            // From above (a death from above) it drives the structure down; else it pushes it away, level.
            if (from.z > bounds.max.z) { flags |= GpuDestruction.LOADED; }
            else { push.set(middle.x - from.x, middle.y - from.y).nor().scl(blast > 0 ? blast : change.strength()); }
        }
        // Its pieces come down to the hex's ground: a hex inside a wide building holds its roof alone.
        bounds.min.z = Math.min(bounds.min.z, middle.z);
        // Where a fragment of it shows, the rule its cuts are asked by, its pieces land as bodies.
        boolean contact = view != null && fragmentPixels(at, view) >= CUT_PIXELS;
        destruction.take(at, pieces, buildings, bounds, middle.x, middle.y, push, flags, contact);
        return chunk;
    }

    /**
     * The kit structures standing at the hex as the pieces they fall in: every part of every node the terrain draws
     * there, but glass, which shatters to nothing; each in a solid copy of its material drawn from both sides (a kit
     * storey is open below), its mesh part's box its node's, about which the piece turns; and what stands on them.
     * {@code taking}: the fall begins now, so the hex holds the cuts its nodes fall in and what stands on its roof rides
     * down; else the same pieces as it would fall in now, for their list to be written ahead (#prepareLists).
     * {@code pieces} null: the take of pieces prepared before, which only does what the take does besides.
     */
    /** A set of kit assemblies by identity (GpuBuilding.Assembly's own hash reads its every placement). */
    static Set<GpuBuilding.Assembly> assemblies() { return java.util.Collections.newSetFromMap(new IdentityHashMap<>()); }

    private void fallPieces(TileMesh mesh, Coords at, boolean taking, Array<Renderable> pieces,
          Set<GpuBuilding.Assembly> buildings, BoundingBox bounds) {
        Map<Material, Material> copies = fallCopies;
        for (Prop prop : mesh.props) {
            if (!structure(prop)) { continue; }
            if (pieces == null) {
                if (prop.building != null) { buildings.add(prop.building); }
                else { carried.add(prop); }
                bounds.ext(prop.bounds());
                continue;
            }
            ModelInstance drawn = prop.instance();
            // Each node in its fragments where the cut its hex asked for is in (KitFragments.Cuts); else whole. A kit's
            // nodes are cut apart, as every building placing them shares them; a structure's own model as one source,
            // its glass breaking with it.
            Function<Node, KitFragments> broken;
            if (prop.building != null) {
                KitFragments.Level level = prop.building.kit().fragmentLevel(drawn.model);
                broken = level == null ? null : node -> cuts.take(level.source(node), at, taking);
                buildings.add(prop.building);
            } else {
                broken = shellCut(drawn.model, at, taking);
                // Its chunk draws it no more from the take on (GpuTerrain.Chunk#cacheProps, #hides).
                if (taking) { carried.add(prop); }
            }
            for (Node node : drawn.nodes) {
                addPieces(drawn, node, copies, pieces, broken, prop.building == null, taking ? destruction::prepared : null);
            }
            bounds.ext(prop.bounds());
        }
        if (pieces != null && pieces.isEmpty()) { return; }
        // What stands on the structure, not on the ground beside it, comes down with its roof. Its interior floors show
        // only in a cutaway, which a falling hex never draws (Chunk#cacheProps).
        for (Prop prop : mesh.props) {
            if (!rides(prop, bounds)) { continue; }
            if (taking) { carried.add(prop); }
            if (pieces == null) { continue; }
            ModelInstance drawn = prop.instance();
            for (Node node : drawn.nodes) { addPieces(drawn, node, copies, pieces, null, false, null); }
        }
    }

    /** Whether the prop stands on the structures {@code bounds} holds, not on the ground beside them: it rides down. */
    private static boolean rides(Prop prop, BoundingBox bounds) {
        return !structure(prop) && !prop.tree() && !GpuTerrain.isFlag(prop) && !prop.floors
              && prop.bounds().min.z >= bounds.min.z + BoardGeometry.level();
    }

    /**
     * Whether the chunk draws the hex's structures, and what rides on them, by reference, none of it in its packed
     * caches: the GPU-driven path then hides them by key, and their pieces may come after the take (#supplyFalls).
     */
    private static boolean byReference(Chunk chunk, TileMesh mesh, BoundingBox bounds) {
        for (Prop prop : mesh.props) {
            if ((structure(prop) || rides(prop, bounds)) && chunk.cutaways.contains(prop) && chunk.packs(prop)) {
                return false;
            }
        }
        return true;
    }

    /** A pending fall gets its pieces once its first piece moves within this much playback (GpuDestruction#due). */
    private static final float DUE_SECONDS = .1f;

    /**
     * The pieces of the falls taken without them (#takeFall): first those whose first piece moves within
     * {@link #DUE_SECONDS}, whatever the time, then the others in the order they move, until {@code deadline}
     * (System#nanoTime). A strike's city thus gets its pieces as its front reaches it, not all in the frame of its take.
     */
    private void supplyFalls(long deadline) {
        List<Coords> waiting = destruction.pending();
        if (waiting.isEmpty()) { return; }
        Set<Chunk> supplied = new LinkedHashSet<>();
        terrain.settings().run(() -> {
            for (Coords at : waiting) {
                if (!destruction.due(at, DUE_SECONDS) && System.nanoTime() >= deadline) { break; }
                Chunk chunk = terrain.chunkOf(at);
                TileMesh mesh = chunk == null ? null : chunk.tileMeshes.get(at);
                Array<Renderable> pieces = new Array<>();
                if (mesh != null) { fallPieces(mesh, at, true, pieces, assemblies(), new BoundingBox().inf()); }
                destruction.supply(at, pieces);
                if (chunk != null) { supplied.add(chunk); }
            }
        });
        // What the take does to a chunk besides hiding the hexes by key (a faded structure's outline depth).
        supplied.forEach(terrain::fallen);
        terrain.stampFalls();
    }

    /**
     * Adds the node's parts and its children's as falling pieces, each node's in the fragments {@code broken} gives for
     * it where they hold the part (null, or none: whole) and their part is placed ({@code built}; null: whatever it is).
     * {@code glass}: its blended parts too, solid.
     */
    private static void addPieces(ModelInstance instance, Node node, Map<Material, Material> copies,
          Array<Renderable> pieces, Function<Node, KitFragments> broken, boolean glass, Predicate<Renderable> built) {
        KitFragments cut = broken == null ? null : broken.apply(node);
        // One box for the node's parts, in its mesh's frame: they turn about it as one piece.
        BoundingBox box = new BoundingBox().inf();
        var corner = new Vector3();
        for (NodePart part : node.parts) {
            if (!part.enabled || part.meshPart.size == 0) { continue; }
            // A part a model builder made may not know its box (MeshPart#update): read from its vertices.
            if (part.meshPart.halfExtents.isZero()) { part.meshPart.update(); }
            box.ext(corner.set(part.meshPart.center).sub(part.meshPart.halfExtents));
            box.ext(corner.set(part.meshPart.center).add(part.meshPart.halfExtents));
        }
        for (NodePart part : node.parts) {
            // Glass is what blends in the model itself: a building faded around its occupants or the pointer still falls.
            Material own = instance.model.getMaterial(part.material.id);
            if (!part.enabled || part.meshPart.primitiveType != GL20.GL_TRIANGLES || part.meshPart.size == 0
                  || !glass && GpuTerrain.blended(own != null ? own : part.material)) { continue; }
            Renderable piece = instance.getRenderable(new Renderable(), node, part);
            if (!GpuTreeInstances.instanceable(piece.worldTransform)) { continue; }
            piece.material = copies.computeIfAbsent(part.material, TerrainFalls::fallMaterial);
            var fragments = cut == null ? null : cut.part(part.meshPart);
            if (fragments != null) {
                // Its fragments in its place: still one part, each vertex moving with its fragment (tree-instances.glsl).
                Mesh whole = piece.meshPart.mesh;
                int offset = piece.meshPart.offset, size = piece.meshPart.size;
                piece.meshPart.set(fragments.range());
                piece.userData = fragments.fragments();
                // A cut that came in too late for its part to be placed ahead: the part falls whole, as it stood.
                if (built != null && !built.test(piece)) {
                    piece.meshPart.set(part.meshPart.id, whole, offset, size, part.meshPart.primitiveType);
                    piece.userData = null;
                }
            }
            box.getCenter(piece.meshPart.center);
            box.getDimensions(piece.meshPart.halfExtents).scl(.5f);
            pieces.add(piece);
        }
        for (Node child : node.getChildren()) { addPieces(instance, child, copies, pieces, broken, glass, built); }
    }

    /**
     * The frame's timeline names what the playback is about to bring down or break. From then on, never at the board's
     * load nor as the fall begins: the lists of the kit structures named are written (#prepareLists); their
     * nodes are cut into fragments on the cutter's threads where a fragment would show ({@link #CUT_PIXELS}), the largest
     * on screen first; and the models of the cover an attack or a blast breaks are split (KitFragments.Cuts).
     * {@code playing}: whether the playback has anything left to play; once it has played out what the timelines named,
     * no fall is awaited any more. Called each frame.
     */
    void prepareFalls(List<BoardScene.Animation> timeline, Camera camera, boolean playing) {
        view = camera;
        destruction.view(camera);
        // A timeline whose events all showed at once (a checkpoint alone) played this frame.
        boolean busy = playing || !timeline.isEmpty();
        if (fallsPlaying && !busy) {
            standings.values().forEach(standing -> standing.future().cancel(false));
            standings.clear();
            cuts.forget(destruction.taken());
            fallsPreparing.clear();
            destruction.retainPrepared(cuts::awaits);
            spans.entrySet().removeIf(span -> {
                boolean forgotten = !spansFalling.containsKey(span.getKey());
                if (forgotten) { span.getValue().dispose(); }
                return forgotten;
            });
        }
        fallsPlaying = busy;
        if (terrain.coverScene == null || terrain.columns()) { return; }
        Set<Coords> named = new HashSet<>();
        for (var event : timeline) {
            if (event instanceof BoardScene.Combat combat) {
                // The hexes its damage brings down, with its blast or as the phase ends.
                for (var at : combat.result().collapses()) {
                    if (at.boardId() == terrain.coverScene.boardId()) { named.add(at.coords()); }
                }
            } else if (event.boardId() != terrain.coverScene.boardId()) {
                continue;
            } else if (event instanceof BoardScene.TileTransition change) {
                change.collapses().forEach(collapse -> named.add(collapse.coords()));
            } else if (event instanceof BoardScene.Detonation detonation) {
                detonation.collapses().forEach(collapse -> named.add(collapse.coords()));
                prepareBreak(detonation.felled(), false);
            } else if (event instanceof BoardScene.SceneUpdate update) {
                prepareBreak(update.broken(), true);
            }
        }
        // A bridge's span: what of the bridge stands once it falls is worked out on the cutter threads from now, and the
        // span read and cut while its chunk still draws it, a few a frame (#readSpans).
        named.forEach(this::askStanding);
        readSpans();
        named.removeIf(at -> standing(at) == null || !cuts.expect(at));
        // Each keeps the level it draws at until its fall (#holds).
        heldLevels.keySet().removeIf(at -> !cuts.awaits(at));
        Set<Chunk> unpack = new LinkedHashSet<>();
        for (Coords at : named) {
            for (Prop prop : standing(at)) {
                if (!structure(prop) || heldLevels.putIfAbsent(at, prop.level()) != null) { continue; }
                // A structure of its own model leaves its chunk's packed caches now, ahead of its fall (#named).
                if (prop.building == null) { unpack.add(terrain.chunkOf(at)); }
            }
        }
        unpack.forEach(terrain::refall);
        // Those named now, and those named before whose cuts are not asked yet: the largest on screen first.
        List<Coords> unasked = new ArrayList<>(cuts.unasked());
        if (unasked.isEmpty()) { return; }
        terrain.settings().run(() -> {
            Map<Coords, Float> pixels = new HashMap<>();
            for (Coords at : unasked) { pixels.put(at, fragmentPixels(at, camera)); }
            unasked.sort(Comparator.comparingDouble(at -> -pixels.get(at)));
            for (Coords at : unasked) {
                if (named.contains(at)) { fallsPreparing.add(at); }
                float shows = pixels.get(at);
                List<Prop> props = shows < CUT_PIXELS / COARSE_SIDES ? null : standing(at);
                if (props == null) { continue; }
                cuts.ask(at);
                props.forEach(prop -> wantCuts(prop, shows));
            }
        });
    }

    /**
     * A structure's nodes are cut where one of their fragments would show at least this large, in scene pixels: a hex
     * about 62 pixels wide. A smaller one falls whole, under its dust. As large when it is taken, its pieces land as
     * bodies (#takeFall).
     */
    private static final float CUT_PIXELS = 6;
    /** The frame's camera (#prepareFalls): a fall taken in the frame is sized in its view. */
    private Camera view;

    /**
     * The cut a shell at the hex falls in: its fine one where a kit-sized fragment shows at least {@link #CUT_PIXELS} in
     * the frame's view, else its coarse one; the other grade where the one wanted is not in, null where neither is (it
     * falls whole).
     */
    private Function<Node, KitFragments> shellCut(Model model, Coords at, boolean taking) {
        boolean fine = view != null && fragmentPixels(at, view) >= CUT_PIXELS;
        KitFragments wanted = cuts.take(KitFragments.grade(model, fine ? KitFragments.FINE : KitFragments.COARSE), at, taking);
        KitFragments cut = wanted != null ? wanted
              : cuts.take(KitFragments.grade(model, fine ? KitFragments.COARSE : KitFragments.FINE), at, false);
        return cut == null ? null : node -> cut;
    }

    /** How large a fragment of the hex's structures would show (KitFragments#SIDE), in scene pixels; 0 out of view. */
    private float fragmentPixels(Coords at, Camera camera) {
        var tile = terrain.coverScene.tile(at);
        Vector3 middle = BoardGeometry.center(at, tile == null ? 0 : tile.elevation());
        if (!camera.frustum.sphereInFrustum(middle, BoardGeometry.width())) { return 0; }
        return KitFragments.SIDE * BoardGeometry.hexScale() * BoardCamera.pixelsPerUnit(camera, middle);
    }

    /** The hex's props where a structure stands in its chunk, not fallen; null where none does. */
    private List<Prop> standing(Coords at) {
        Chunk chunk = terrain.chunkOf(at);
        TileMesh mesh = chunk == null || destruction.taken(at) ? null : chunk.tileMeshes.get(at);
        return mesh == null || mesh.props.stream().noneMatch(TerrainFalls::structure) ? null : mesh.props;
    }

    /**
     * Whether the prop is a structure that falls in pieces: a kit building's or fuel tank's, or a building, fuel tank,
     * dome or wall drawn from a model of its own (an authored share, a single model) whose parts instances can hold, as
     * its chunk then draws it (GpuTerrain.Chunk#cacheProps); never its interior floors, a flag or a bridge's span, which
     * falls as nodes of a model of its own (#span).
     */
    static boolean structure(Prop prop) {
        return !GpuTerrain.isFlag(prop) && (prop.building != null || !prop.floors
              && ("building".equals(prop.receiver) || "fuelTank".equals(prop.receiver)) && prop.instanced());
    }

    /**
     * A shell's coarse pieces are this many times the kit-sized fine ones across (KitFragments#COARSE): they show from
     * this much farther off.
     */
    private static final float COARSE_SIDES = 3;

    /**
     * Asks for the cuts of the nodes the structure draws now, for its hex's fall to come, where a kit-sized fragment
     * shows {@code shows} pixels: a kit's where that is at least {@link #CUT_PIXELS}; a shell's coarse cut, and its fine
     * one too where that is.
     */
    private void wantCuts(Prop prop, float shows) {
        if (!structure(prop)) { return; }
        ModelInstance drawn = prop.instance();
        if (prop.building == null) {
            Model model = drawn.model;
            for (int grade : shows >= CUT_PIXELS ? new int[] { KitFragments.FINE, KitFragments.COARSE }
                  : new int[] { KitFragments.COARSE }) {
                cuts.want(KitFragments.grade(model, grade), prop.coords(), () -> KitFragments.later(
                      KitFragments.shell(model, side(drawn), glass(model), grade), "A structure falls whole: its cut failed"));
            }
            return;
        }
        if (shows < CUT_PIXELS) { return; }
        KitFragments.Level level = prop.building.kit().fragmentLevel(drawn.model);
        if (level != null) { cuts.want(level, drawn.nodes, prop.coords()); }
    }

    /** A fragment's side ({@link KitFragments#SIDE}, in a kit's units) in the units of the placement's model. */
    private static float side(ModelInstance placed) {
        float[] m = placed.transform.val;
        float scale = (float) Math.hypot(m[Matrix4.M00], m[Matrix4.M10]);
        return KitFragments.SIDE * BoardGeometry.hexScale() / Math.max(scale, 1e-6f);
    }

    /** The ids of the model's glass: what blends in the model itself. */
    private static Set<String> glass(Model model) {
        Set<String> result = new HashSet<>();
        for (Material material : model.materials) {
            if (GpuTerrain.blended(material)) { result.add(material.id); }
        }
        return result;
    }

    /**
     * The level of detail each kit structure about to fall drew at as its fall was named. It keeps that level until the
     * fall is taken or forgotten (#holds, #hold), so its cuts and its list are made once, for the pieces it will fall
     * in, however the camera moves and whichever chunk a rebuild installs meanwhile.
     */
    private final Map<Coords, Integer> heldLevels = new HashMap<>();

    /** The placements whose nodes' cuts were asked ahead (#store): each once, while the terrain draws it. */
    private final Set<ModelInstance> storedPlacements = Collections.newSetFromMap(new java.util.WeakHashMap<>());
    /** Kit levels up to this one are cut ahead: a structure drawn at a farther one shows too small for fragments. */
    private static final int STORED_LEVEL = 1;

    /**
     * The cuts of the kit nodes the structure draws now are made and kept on disk in the background, unless they
     * are there (KitFragments#storeLater): a fall named later reads them back instead of cutting against the clock.
     */
    void store(Prop prop) {
        if (!structure(prop) || prop.level() > STORED_LEVEL || !storedPlacements.add(prop.instance())) { return; }
        Model model = prop.instance().model;
        if (prop.building == null) {
            KitFragments.storeLater(model, side(prop.instance()), glass(model));
            return;
        }
        KitFragments.Level level = prop.building.kit().fragmentLevel(model);
        if (level != null) { store(level, prop.instance().nodes); }
    }

    private static void store(KitFragments.Level level, Iterable<Node> nodes) {
        for (Node node : nodes) {
            KitFragments.Level.Source source = level.source(node);
            if (source != null) { KitFragments.storeLater(source); }
            store(level, node.getChildren());
        }
    }

    /**
     * Whether the hex's fall is named and to come, its level held: its chunk draws a structure of its own model there by
     * reference from then on, not packed (GpuTerrain.Chunk#cacheProps).
     */
    boolean named(Coords at) { return heldLevels.containsKey(at); }

    /** Whether the structure keeps its level of detail for its fall to come: the zoom does not change it. */
    boolean holds(Prop prop) {
        return structure(prop) && cuts.awaits(prop.coords()) && heldLevels.containsKey(prop.coords());
    }

    /**
     * A structure of a chunk just installed takes the level its hex's fall was prepared at, and its hex's pieces are
     * looked over for its new instance (#prepareLists): those of its level are placed. Whether that changed what it draws.
     */
    boolean hold(Prop prop) {
        if (!holds(prop)) { return false; }
        fallsPreparing.add(prop.coords());
        return prop.hold(heldLevels.get(prop.coords()));
    }

    /** The falls to come whose pieces' lists are to be written, or written again with the cuts that came in. */
    private final Set<Coords> fallsPreparing = new LinkedHashSet<>();
    /**
     * A frame prepares the falls to come for at most this long (their pieces and their lists): a city's 697
     * hexes are about a tenth of a second's work, which a strike's 40 frames before its front arrives must hold. A fall
     * taken before it is prepared makes its pieces then, whole where a part is not ready.
     */
    private static final long PREPARE_NANOS = 4_000_000L;

    /**
     * Writes the lists the falls to come will draw (GpuDestruction#prepare), each part placed in the pools, hex by hex
     * within the frame's share of time: the frame that takes a fall then finds every one, whole parts and fragments
     * alike, and places none.
     */
    void prepareLists() {
        destruction.bindRows();
        fallsPreparing.addAll(cuts.install());
        long deadline = System.nanoTime() + PREPARE_NANOS;
        // The falls taken come before those to come: theirs are due as their front arrives.
        supplyFalls(deadline);
        supplyRubble(deadline);
        if (fallsPreparing.isEmpty()) {
            destruction.build(deadline);
            return;
        }
        terrain.settings().run(() -> {
            var hexes = fallsPreparing.iterator();
            while (hexes.hasNext() && destruction.build(deadline)) {
                Coords at = hexes.next();
                hexes.remove();
                Chunk chunk = cuts.awaits(at) && !destruction.taken(at) ? terrain.chunkOf(at) : null;
                TileMesh mesh = chunk == null ? null : chunk.tileMeshes.get(at);
                if (mesh == null) { continue; }
                Array<Renderable> pieces = new Array<>();
                fallPieces(mesh, at, false, pieces, assemblies(), new BoundingBox().inf());
                destruction.prepare(at, pieces, mesh);
            }
            destruction.build(deadline);
        });
    }

    /**
     * The rubble the rules leave where a structure fell, scenery the installed scene now draws on its hex, waits out of
     * sight under the falling pieces and rises under their dust (GpuDestruction#rubble). Its chunk draws it again once
     * its fall is over. Called when chunks are installed: theirs are new props. Its chunk hides it from now on; its
     * rising pieces are made in the frames after this one (#supplyRubble), long before it rises.
     */
    void takeRubble() {
        for (Coords at : destruction.fallen()) {
            Chunk chunk = terrain.chunkOf(at);
            TileMesh mesh = chunk == null ? null : chunk.tileMeshes.get(at);
            if (mesh == null) { continue; }
            for (Prop prop : mesh.props) {
                if (prop.rubble && rising.add(prop)) { rubbleTaken.add(at); }
            }
        }
    }

    /**
     * The hexes whose rubble waits for its rising pieces: those taken since the last frame's share (#takeRubble), and
     * those of the frames before, which this frame's share makes (#supplyRubble).
     */
    private final Set<Coords> rubbleTaken = new LinkedHashSet<>(), rubbleWaiting = new LinkedHashSet<>();

    /**
     * The rising pieces of the rubble that waits for them, hex by hex until {@code deadline} (System#nanoTime), never
     * in the frame that took it: a strike's commit, and the frame that takes a bridge's span, make none.
     */
    private void supplyRubble(long deadline) {
        if (!rubbleWaiting.isEmpty()) { makeRubble(deadline); }
        rubbleWaiting.addAll(rubbleTaken);
        rubbleTaken.clear();
    }

    private void makeRubble(long deadline) {
        terrain.settings().run(() -> {
            for (var waiting = rubbleWaiting.iterator(); waiting.hasNext() && System.nanoTime() < deadline; ) {
                Coords at = waiting.next();
                waiting.remove();
                Chunk chunk = terrain.chunkOf(at);
                TileMesh mesh = chunk == null ? null : chunk.tileMeshes.get(at);
                var tile = terrain.coverScene == null ? null : terrain.coverScene.tile(at);
                if (mesh == null || tile == null) { continue; }
                Array<Renderable> pieces = new Array<>();
                BoundingBox bounds = new BoundingBox().inf();
                Map<Material, Material> copies = new IdentityHashMap<>();
                for (Prop prop : mesh.props) {
                    if (!prop.rubble) { continue; }
                    ModelInstance drawn = prop.instance();
                    for (Material material : drawn.materials) { copies.computeIfAbsent(material, GpuTerrain::risingMaterial); }
                    for (Node node : drawn.nodes) { addPieces(drawn, node, copies, pieces, null, false, null); }
                    bounds.ext(prop.bounds());
                }
                if (pieces.isEmpty()) { continue; }
                var middle = BoardGeometry.center(at, tile.elevation());
                destruction.rubble(at, pieces, bounds, middle.x, middle.y);
            }
        });
    }

    /**
     * A falling piece's copy of a drawn material: solid, whatever fade its building wore, from both sides. It is not
     * changed once made, so it hashes once: the fall lists look every piece up by its material (a strike's 91,670
     * parts in one frame).
     */
    static Material fallMaterial(Material drawn) {
        var copy = new GpuFixedMaterial(drawn);
        GpuTerrain.opaque(copy);
        copy.set(IntAttribute.createCullFace(GL20.GL_NONE));
        return copy;
    }

    /**
     * The falls' copies of drawn materials (#fallMaterial), one a drawn material while anything falls or is prepared to:
     * a city's hexes share them, so their pieces compare equal by identity.
     */
    private final Map<Material, Material> fallCopies = new IdentityHashMap<>();

    /** The scene whose hexes' floors the falls' bodies land on (GpuDestruction#floors): read again when it changes. */
    private BoardScene floorsOf;

    /** Runs the falls' clock on by {@code seconds} of playback under {@code gravity} standard gravities. */
    void advanceFalls(float seconds, float gravity) {
        if (destruction.lands() && floorsOf != terrain.coverScene && terrain.coverScene != null) {
            floorsOf = terrain.coverScene;
            int columns = floorsOf.width(), rows = floorsOf.height();
            float[] heights = new float[columns * rows];
            for (int x = 0; x < columns; x++) {
                for (int y = 0; y < rows; y++) {
                    var tile = floorsOf.tile(x, y);
                    // HexTile#at's order, the scene's own.
                    heights[x * rows + y] = tile == null ? -Float.MAX_VALUE
                          : tile.liquid().present() && !tile.frozen() ? BoardGeometry.groundZ(tile) + .5f
                          : BoardGeometry.center(tile.coords(), tile.elevation()).z;
                }
            }
            destruction.floors(new MeepleDebris.Floors(heights, columns, rows, BoardGeometry.width(), BoardGeometry.height()));
        }
        int before = destruction.revision();
        destruction.advance(seconds, gravity, terrain.coverScene == null ? 0 : terrain.coverScene.width(),
              terrain.coverScene == null ? 0 : terrain.coverScene.height());
        if (destruction.isEmpty() && fallsPreparing.isEmpty() && !cuts.pending()) { fallCopies.clear(); }
        // A pile that sank lay on a hex its chunk already shows without the structure.
        if (destruction.revision() != before) {
            terrain.fallsOver();
            carried.removeIf(prop -> !destruction.taken(prop.coords));
            rising.removeIf(prop -> !destruction.taken(prop.coords));
            broken.removeIf(prop -> !destruction.felled(prop.instance().transform));
            // Its hex holds its cuts no longer: they stay for the next fall while the budget has room.
            cuts.retain(destruction.taken());
        }
    }

    /**
     * A strike going off now: the kit structures it brings down leave their chunks for their pieces, and each piece gives
     * way as its front reaches it (GpuArtilleryBlast#shockArrival, on the blast's clock, twice the playback's). Classic
     * columns snap.
     */
    void strike(BoardScene.Detonation detonation) {
        if (terrain.coverScene == null || terrain.columns()
              || detonation.collapses().isEmpty() && detonation.felled().isEmpty()) {
            return;
        }
        var result = detonation.result();
        float radius = GpuArtilleryBlast.nuclearRadius(result.stats());
        var groundZero = BoardGeometry.center(result.coords(), result.elevation());
        // Chunks whose structures and trees are taken, and those whose cover breaks into pieces: snapshotted again.
        Set<Chunk> taken = new LinkedHashSet<>(), broke = new LinkedHashSet<>();
        terrain.settings().run(() -> {
            for (var collapse : detonation.collapses()) {
                if (destruction.taken(collapse.coords())) { continue; }
                var middle = BoardGeometry.center(collapse.coords(), collapse.before().elevation());
                float distance = Vector2.dst(middle.x, middle.y, groundZero.x, groundZero.y);
                // Stronger toward the epicentre: up to four heavy hits' push beside it, one at the blast's edge. A
                // conventional explosion pushes as its hit does (TileTransition#blast), never more than a heavy hit.
                float near = 1 - MathUtils.clamp(distance / radius, 0, 1);
                Chunk chunk = takeFall(collapse, result.kind() == megamek.common.NuclearDetonation.Kind.EXPLOSION ? 0
                      : 1 + 3 * near * near);
                if (chunk == null) { continue; }
                taken.add(chunk);
                destruction.blastFrom(collapse.coords(), groundZero.x, groundZero.y,
                      GpuArtilleryBlast.shockSlowness(distance, radius) / GpuArtilleryBlast.SECONDS_PER_PLAYBACK);
                // A fuel tank an explosion names is the one that went off in it (AttackAnimationCapture): it bursts.
                if (result.kind() == megamek.common.NuclearDetonation.Kind.EXPLOSION
                      && collapse.before().cover().structure() == BoardScene.Cover.FUEL_TANK) {
                    destruction.burst(collapse.coords());
                }
                destruction.start(collapse.coords(),
                      GpuArtilleryBlast.shockArrival(distance, radius) / GpuArtilleryBlast.SECONDS_PER_PLAYBACK);
            }
            for (var grove : detonation.felled()) {
                var middle = BoardGeometry.center(grove.coords(), 0);
                float distance = Vector2.dst(middle.x, middle.y, groundZero.x, groundZero.y);
                float arrival = GpuArtilleryBlast.shockArrival(distance, radius) / GpuArtilleryBlast.SECONDS_PER_PLAYBACK;
                // What stands in no stand (crystals, a skin's rows) bursts as the front reaches its hex.
                Chunk burst = breakGrove(grove, groundZero, arrival, false);
                if (burst != null) { broke.add(burst); }
                Chunk chunk = takeTrees(grove, groundZero);
                if (chunk == null) { continue; }
                taken.add(chunk);
                destruction.blastFrom(grove.coords(), groundZero.x, groundZero.y,
                      GpuArtilleryBlast.shockSlowness(distance, radius) / GpuArtilleryBlast.SECONDS_PER_PLAYBACK);
                destruction.startTrees(grove.coords(), arrival);
            }
        });
        if (!taken.isEmpty() || !broke.isEmpty()) {
            taken.removeAll(broke);
            taken.forEach(terrain::fallen);
            broke.forEach(terrain::refall);
            terrain.refreshShadows(destruction.takenSince());
        }
        terrain.stampFalls();
    }

    /**
     * A nuclear round's blast going off now (a Davy Crockett-M fired in combat): what its attack brought down falls with
     * its front from where it landed, as a scheduled strike's does, not at the phase's end.
     */
    void strike(megamek.common.ResolvedAttack result, Coords landing) {
        var stats = GpuArtilleryBlast.nuclearStats(result.shot());
        var ground = terrain.coverScene == null ? null : terrain.coverScene.tile(landing);
        if (stats == null || ground == null) { return; }
        // At the hex's level: the round's impact height adds its target's, which would drive the structures down.
        var record = new megamek.common.NuclearDetonation(result.id(), terrain.coverScene.boardId(), landing,
              ground.elevation(), stats.baseDamage, stats.degradation, stats.secondaryRadius, stats.craterDepth);
        List<BoardScene.TileTransition.Collapse> falls = new ArrayList<>();
        for (var at : result.collapses()) {
            var tile = at.boardId() == terrain.coverScene.boardId() ? terrain.coverScene.tile(at.coords()) : null;
            if (tile != null && BoardScene.TileTransition.structure(tile)) {
                falls.add(BoardScene.TileTransition.collapse(terrain.coverScene, tile,
                      BoardScene.TileTransition.blast(record, at.coords())));
            }
        }
        if (!falls.isEmpty()) { strike(new BoardScene.Detonation(record, falls)); }
    }

    /**
     * Fells the hex's stand trees the blast clears ({@code grove}, as its scene's features): they leave their chunk's
     * stand to fall away from ground zero (GpuDestruction). The chunk that drew them, or null where none stands.
     */
    private Chunk takeTrees(BoardScene.Detonation.Felled grove, Vector3 groundZero) {
        Coords at = grove.coords();
        Chunk chunk = terrain.chunkOf(at);
        if (chunk == null) { return null; }
        Set<BoardScene.Feature> features = Set.copyOf(grove.trees());
        List<GpuDestruction.Tree> trees = new ArrayList<>();
        BoundingBox bounds = new BoundingBox().inf();
        for (Prop prop : GpuTerrain.grovesTrees(chunk, grove)) {
            if (!prop.standing()) { continue; }
            trees.add(new GpuDestruction.Tree(prop.treeAsset, new Matrix4(prop.instance().transform), prop.treeDiameter));
            bounds.ext(prop.bounds());
        }
        if (trees.isEmpty()) { return null; }
        var middle = BoardGeometry.center(at, 0);
        var push = new Vector2(middle.x - groundZero.x, middle.y - groundZero.y);
        if (push.len2() < 1e-6f) { push.set(1, 0); }
        destruction.takeTrees(at, trees, features, bounds, middle.x, middle.y, push.nor());
        return chunk;
    }

    /**
     * Splits the models of the cover about to break into their fragments, ahead of its hit (#breakGrove): what draws on
     * its own, and with {@code trees} its stand's trees too, which an attack breaks and a blast only fells.
     */
    void prepareBreak(List<BoardScene.Detonation.Felled> broken, boolean trees) {
        for (var grove : broken) {
            Chunk chunk = terrain.chunkOf(grove.coords());
            if (chunk == null) { continue; }
            for (Prop prop : GpuTerrain.grovesTrees(chunk, grove)) {
                if (prop.standing() && !trees) { continue; }
                Model model = prop.instance().model;
                cuts.want(model, grove.coords(), () -> KitFragments.splitLater(model));
            }
        }
    }

    /**
     * The woods cover an attack took down (its checkpoint's, GpuBoardSource#publishScene: trees, crystals, a skin's rows)
     * breaks as its hit lands: each in its fragments (whole parts where its split is not in yet), they burst away from
     * {@code hit} and out (GpuDestruction#breakTrees) and sink once the terrain shows them gone.
     */
    void breakTrees(List<BoardScene.Detonation.Felled> broken, BoardScene.TileTransition.Hit hit) {
        if (terrain.coverScene == null || terrain.columns() || broken.isEmpty() || hit == null) { return; }
        var from = BoardGeometry.center(hit.from().coords(), hit.from().elevation());
        Set<Chunk> taken = new LinkedHashSet<>();
        terrain.settings().run(() -> {
            for (var grove : broken) {
                Chunk chunk = breakGrove(grove, from, 0, true);
                if (chunk != null) { taken.add(chunk); }
            }
        });
        if (!taken.isEmpty()) {
            taken.forEach(terrain::refall);
            terrain.refreshShadows(destruction.takenSince());
        }
        terrain.stampFalls();
    }

    /**
     * Breaks the hex's cover into pieces that burst away from {@code from}, {@code delay} playback seconds from now:
     * what draws on its own, and with {@code trees} its stand's trees too. The chunk that drew it, or null for none.
     */
    private Chunk breakGrove(BoardScene.Detonation.Felled grove, Vector3 from, float delay, boolean trees) {
        Coords at = grove.coords();
        Chunk chunk = terrain.chunkOf(at);
        if (chunk == null) { return null; }
        Array<Renderable> pieces = new Array<>();
        List<Matrix4> stood = new ArrayList<>();
        List<Prop> alone = new ArrayList<>();
        BoundingBox bounds = new BoundingBox().inf();
        Map<Material, Material> copies = new IdentityHashMap<>();
        for (Prop prop : GpuTerrain.grovesTrees(chunk, grove)) {
            if (prop.standing() && !trees) { continue; }
            ModelInstance drawn = prop.instance();
            // In its model's fragments where its split is in (KitFragments.Cuts); else in whole parts.
            KitFragments split = cuts.take(drawn.model, at, true);
            Function<Node, KitFragments> cut = split == null ? null : node -> split;
            for (Node node : drawn.nodes) { addPieces(drawn, node, copies, pieces, cut, true, null); }
            stood.add(new Matrix4(drawn.transform));
            if (!prop.standing()) { alone.add(prop); }
            bounds.ext(prop.bounds());
        }
        if (pieces.isEmpty()) { return null; }
        var middle = BoardGeometry.center(at, 0);
        var push = new Vector2(middle.x - from.x, middle.y - from.y);
        if (push.len2() < 1e-6f) { push.set(1, 0); }
        destruction.breakTrees(at, pieces, stood, Set.copyOf(grove.trees()), bounds, middle.x, middle.y, push.nor(), delay);
        broken.addAll(alone);
        return chunk;
    }

    /** At most this many triangles of a bridge's span and what it carries are cut: their finest level within it. */
    private static final int SPAN_TRIANGLES = 30_000;
    /** A span breaks into pieces about a lane wide: this many steep breaks across it (MeepleShatter#crumble). */
    private static final int SPAN_BREAKS = 7;

    /**
     * A bridge's span about to fall: its cut, made on the cutter threads from its chunk as the fall was named (#span),
     * the masks its road coats sample, which its pieces own once it falls, and whether it falls into water.
     */
    private record Span(java.util.concurrent.CompletableFuture<MeepleShatter.Cut> cut, GpuTextures<BoardScene.Pixels> masks,
          boolean water) {
        void dispose() {
            cut.cancel(false);
            if (masks != null) { masks.dispose(); }
        }
    }

    /** The spans whose falls were named, by hex; and those a transition brought down, waiting to be shown gone. */
    private final Map<Coords, Span> spans = new HashMap<>();
    private final Map<Coords, BoardScene.TileTransition.Collapse> spansFalling = new LinkedHashMap<>();

    /**
     * Reads and starts cutting the hex's bridge span now, unless it has none; whether it has one. What stands of it is
     * taken from the cutter threads where it was asked (#askStanding), waiting for it, else worked out here.
     */
    private boolean prepareSpan(Coords at) {
        PendingStanding pending = standings.remove(at);
        if (spans.containsKey(at)) { return true; }
        BoardScene.Tile tile = spansFalling.containsKey(at) || destruction.taken(at) ? null : spanTile(at);
        if (tile == null) {
            if (pending != null) { pending.future().cancel(false); }
            return false;
        }
        Chunk chunk = terrain.chunkOf(at);
        Standing standing = pending != null && pending.lod() == chunk.lod && !pending.future().isCompletedExceptionally()
              && !pending.future().isCancelled() ? pending.future().join()
              : standing(terrain.coverScene, tile, chunk.lod, terrain.columns());
        Span span = span(at, tile, standing);
        if (span != null) { spans.put(at, span); }
        return spans.containsKey(at);
    }

    /** What stands of a span named for its fall, worked out on the cutter threads at its chunk's level then. */
    private record PendingStanding(TerrainLod lod, java.util.concurrent.CompletableFuture<Standing> future) { }

    /** The named spans whose standing parts are being worked out (#askStanding), by hex. */
    private final Map<Coords, PendingStanding> standings = new HashMap<>();

    /** A frame reads the named spans whose standing parts are in for at most this long (#readSpans). */
    private static final long SPAN_NANOS = 4_000_000L;

    /**
     * Starts working out, on the cutter threads, what of the hex's bridge stands once its span falls (#standing): the
     * bridge footing's ruin and the road banks it reads took a city strike's naming frame half a second.
     */
    private void askStanding(Coords at) {
        if (spans.containsKey(at) || standings.containsKey(at) || spansFalling.containsKey(at) || destruction.taken(at)) {
            return;
        }
        BoardScene.Tile tile = spanTile(at);
        if (tile == null) { return; }
        BoardScene scene = terrain.coverScene;
        TerrainLod lod = terrain.chunkOf(at).lod;
        boolean columns = terrain.columns();
        TerrainSettings settings = terrain.settings();
        standings.put(at, new PendingStanding(lod, KitFragments.later(
              () -> settings.call(() -> standing(scene, tile, lod, columns)),
              "A bridge's standing parts could not be worked out: they are worked out as it falls")));
    }

    /** Reads the named spans whose standing parts are in, within {@link #SPAN_NANOS}, while their chunks draw them. */
    private void readSpans() {
        if (standings.isEmpty()) { return; }
        long deadline = System.nanoTime() + SPAN_NANOS;
        terrain.settings().run(() -> {
            for (var entry : List.copyOf(standings.entrySet())) {
                if (System.nanoTime() >= deadline) { return; }
                if (entry.getValue().future().isDone()) { prepareSpan(entry.getKey()); }
            }
        });
    }

    /**
     * The installed bridge span at the hex, read where its chunk draws it: its deck, piers and footing, the rails and road
     * it carries, at their finest level within {@link #SPAN_TRIANGLES}, in solid copies of their materials (a road's
     * coats still blend over the deck), closed in the bridge's concrete, its cut started on the cutter threads. Null where
     * no span stands.
     */
    private Span span(Coords at, BoardScene.Tile tile, Standing standing) {
        Chunk chunk = terrain.chunkOf(at);
        TileMesh mesh = chunk.tileMeshes.get(at);
        // The cut's frame lies at the hex's foot: its coordinates stay small however far out on the board it stands.
        var origin = BoardGeometry.center(at, tile.elevation());
        var frame = new Matrix4().setToTranslation(-origin.x, -origin.y, -origin.z);
        List<MeepleShatter.Source> sources = new ArrayList<>();
        Map<Material, Material> copies = new IdentityHashMap<>();
        // A rail, maglev beam, road or object the span carries, not one on the ground under it.
        float deck = BoardGeometry.surfaceZ(tile) + BoardGeometry.level() * .5f;
        for (Prop prop : mesh.props) {
            var placed = prop.decorationFeature == null ? null : prop.decorationFeature.decoration();
            boolean carried = prop.bounds().min.z > deck
                  && (prop.route() || placed != null && megamek.common.board.RailRoute.onDeck(placed));
            if (!carried || structure(prop) || GpuTerrain.isFlag(prop)) { continue; }
            ModelInstance drawn = GpuTerrain.finest(prop, SPAN_TRIANGLES);
            for (Node node : drawn.nodes) { addSources(node, drawn, frame, copies, sources); }
        }
        // A road's coats on the deck, in the chunk's overlay layer, sample masks that go with the chunk: the span keeps
        // copies of them (coatSource), and their meshes until the cut has read them.
        GpuTextures<BoardScene.Pixels> masks = null;
        List<Mesh> coatMeshes = new ArrayList<>();
        Array<Renderable> solid = GpuTerrainDepth.snapshot(chunk.ground.opaque), overlays = GpuTerrainDepth.snapshot(chunk.ground.overlays);
        Map<TileRange, Material> coats = new LinkedHashMap<>();
        // Its landings and piers stand on as its ruin (BoardBridgeFooting#ruin): only the span falls.
        for (TileRange range : mesh.ranges) {
            if (!range.route() || range.layer() != Layer.SOLID && range.layer() != Layer.OVERLAY) { continue; }
            Material material = drawnMaterial(range.layer() == Layer.SOLID ? solid : overlays, range);
            if (material == null) { continue; }
            if (range.layer() == Layer.SOLID) {
                var span = falling(range, standing.triangles(), coatMeshes);
                if (span != null) {
                    sources.add(new MeepleShatter.Source(span, copies.computeIfAbsent(material, TerrainFalls::pieceMaterial),
                          frame, 0));
                }
            } else if (range.material().has(GpuRoads.Mask.TYPE)) {
                coats.put(range, material);
            }
        }
        if (!coats.isEmpty()) {
            masks = new GpuTextures<>();
            Map<BoardScene.Pixels, BoardScene.Pixels> pixels = new HashMap<>();
            for (TileRange range : coats.keySet()) {
                var data = range.material().get(GpuRoads.Mask.class, GpuRoads.Mask.TYPE).data.pixels();
                pixels.put(data, data);
            }
            masks.update(pixels);
            Map<List<Object>, Material> coatMaterials = new HashMap<>();
            // The paint the deck carried falls with it: none on the ground under it, on its standing landings and
            // ramps, or on a bank its apron reaches.
            Predicate<Vector3[]> carried = corners -> {
                float x = (corners[0].x + corners[1].x + corners[2].x) / 3;
                float y = (corners[0].y + corners[1].y + corners[2].y) / 3;
                var under = BoardGeometry.tile(terrain.coverScene, x, y);
                return (corners[0].z + corners[1].z + corners[2].z) / 3 > deck && under != null && under.coords().equals(at)
                      && !standing.tops().contains(triangleKey(corners[0], corners[1], corners[2], false));
            };
            for (var coat : coats.entrySet()) {
                var source = coatSource(coat.getKey(), coat.getValue(), masks, frame, coatMaterials, coatMeshes, carried);
                if (source != null) { sources.add(source); }
            }
        }
        try {
            if (sources.isEmpty()) {
                if (masks != null) { masks.dispose(); }
                return null;
            }
            var caps = new MeepleShatter.Caps(
                  GpuTerrain.material(assets.material("terrain/surfaces/concrete-ground"), false),
                  MeshBatch.STANDARD, BoardGeometry.width() / 5 * assets.coverage("terrain/surfaces/concrete-ground"));
            // Their programs are made ready now, ahead of its fall, as any material the terrain is about to draw.
            for (var source : sources) { terrain.prepareTerrainMaterial(source.material(), source.part().mesh.getVertexAttributes()); }
            terrain.prepareTerrainMaterial(caps.material(), caps.attributes());
            // Read here, on the thread that owns the meshes; cut on the cutter threads. No support points: its pieces
            // land as boxes.
            var cutter = MeepleShatter.cutter(sources, origin, Vector3.Z, MeepleShatter.Planes.crumble(0, SPAN_BREAKS),
                  at.hashCode(), caps, false);
            boolean water = tile.liquid().present() && !tile.frozen();
            return new Span(KitFragments.later(cutter, "A bridge's span falls whole: its cut failed"), masks, water);
        } finally {
            // The cut has read them.
            coatMeshes.forEach(Mesh::dispose);
        }
    }

    /**
     * What stands of a built bridge once its span falls (BoardBridgeFooting#ruin): its {@code triangles}
     * ({@link #triangleKey}), and in plan its {@code tops}, which keep the road's paint that lies on them.
     */
    private record Standing(Set<String> triangles, Set<String> tops) {
        static final Standing NONE = new Standing(Set.of(), Set.of());
    }

    /** The hex's tile where a built bridge's span stands in its chunk, its fall not taken; else null. */
    private BoardScene.Tile spanTile(Coords at) {
        BoardScene.Tile tile = terrain.coverScene.tile(at);
        Chunk chunk = tile == null ? null : terrain.chunkOf(at);
        TileMesh mesh = chunk == null ? null : chunk.tileMeshes.get(at);
        return mesh == null || mesh.bridgeShape == null || !BoardScene.TileTransition.structure(tile)
              || tile.cover().structure() != 0 ? null : tile;
    }

    /**
     * What stands of the tile's built bridge once its span falls; nothing of a rock arch or a guideway. Any thread: it
     * reads the scene alone, under the terrain's settings (TerrainSettings#call).
     */
    private static Standing standing(BoardScene scene, BoardScene.Tile tile, TerrainLod lod, boolean columns) {
        var deck = BoardBridge.deck(scene, tile);
        if (deck.natural() || BoardBridge.guideway(tile)) { return Standing.NONE; }
        Map<Coords, BoardSurface> surfaces = new HashMap<>();
        var ruin = BoardBridgeFooting.build(scene, tile, lod, surfaces, columns)
              .ruin(scene, tile, deck, lod, surfaces, columns);
        Set<String> triangles = new HashSet<>(), tops = new HashSet<>();
        for (var facet : ruin.facets()) {
            triangles.add(triangleKey(facet.a(), facet.b(), facet.c(), true));
            if (facet.part() == BoardBridge.Part.TOP) { tops.add(triangleKey(facet.a(), facet.b(), facet.c(), false)); }
        }
        return new Standing(triangles, tops);
    }

    /** A triangle by its corners, in any order, to a hundredth of a world unit; in plan without its {@code height}. */
    private static String triangleKey(Vector3 a, Vector3 b, Vector3 c, boolean height) {
        String[] corners = { corner(a, height), corner(b, height), corner(c, height) };
        Arrays.sort(corners);
        return String.join("|", corners);
    }

    private static String corner(Vector3 p, boolean height) {
        return Math.round(p.x * 100) + "," + Math.round(p.y * 100) + (height ? "," + Math.round(p.z * 100) : "");
    }

    /**
     * A range's vertices and indices copied out of its mesh: {@code points} filled, the indices returned, local to them.
     */
    private static com.badlogic.gdx.utils.ShortArray copied(TileRange range, com.badlogic.gdx.utils.FloatArray points) {
        var indices = new com.badlogic.gdx.utils.ShortArray();
        GpuPropBatch.copyVertices(range.mesh(), range.offset(), range.count(), points, indices);
        return indices;
    }

    /**
     * The triangles of a range's copy ({@code points} and {@code indices}, laid out as {@code attributes}) that
     * {@code keep} takes by their corners.
     */
    private static com.badlogic.gdx.utils.ShortArray kept(com.badlogic.gdx.utils.FloatArray points,
          com.badlogic.gdx.utils.ShortArray indices, VertexAttributes attributes, Predicate<Vector3[]> keep) {
        int stride = attributes.vertexSize / Float.BYTES, position = attributes.getOffset(VertexAttributes.Usage.Position);
        var kept = new com.badlogic.gdx.utils.ShortArray();
        Vector3[] corners = { new Vector3(), new Vector3(), new Vector3() };
        for (int i = 0; i + 2 < indices.size; i += 3) {
            for (int k = 0; k < 3; k++) {
                int at = indices.get(i + k) * stride + position;
                corners[k].set(points.get(at), points.get(at + 1), points.get(at + 2));
            }
            if (keep.test(corners)) { kept.add(indices.get(i), indices.get(i + 1), indices.get(i + 2)); }
        }
        return kept;
    }

    /**
     * A bridge range's triangles but those {@code standing} after its span falls, as a mesh of their own ({@code owned},
     * the caller's to dispose once the cut has read it); the range itself when nothing of it stands, null when all of it
     * does.
     */
    private static com.badlogic.gdx.graphics.g3d.model.MeshPart falling(TileRange range, Set<String> standing,
          List<Mesh> owned) {
        if (standing.isEmpty()) {
            return new com.badlogic.gdx.graphics.g3d.model.MeshPart("bridge", range.mesh(), range.offset(), range.count(),
                  GL20.GL_TRIANGLES);
        }
        var points = new com.badlogic.gdx.utils.FloatArray();
        var indices = copied(range, points);
        var attributes = range.mesh().getVertexAttributes();
        int stride = attributes.vertexSize / Float.BYTES;
        var kept = kept(points, indices, attributes,
              corners -> !standing.contains(triangleKey(corners[0], corners[1], corners[2], true)));
        if (kept.isEmpty()) { return null; }
        if (kept.size == indices.size) {
            return new com.badlogic.gdx.graphics.g3d.model.MeshPart("bridge", range.mesh(), range.offset(), range.count(),
                  GL20.GL_TRIANGLES);
        }
        var copy = new Mesh(Mesh.VertexDataType.VertexArray, false, points.size / stride, kept.size, attributes);
        copy.setVertices(points.toArray());
        copy.setIndices(kept.toArray());
        owned.add(copy);
        return new com.badlogic.gdx.graphics.g3d.model.MeshPart("bridge", copy, 0, kept.size, GL20.GL_TRIANGLES);
    }

    /**
     * The material {@code drawn}, a chunk layer's parts, draws {@code range} with: the chunk's own copy, as it finished
     * it. Null when none draws it.
     */
    private static Material drawnMaterial(Array<Renderable> drawn, TileRange range) {
        for (Renderable part : drawn) {
            var mesh = part.meshPart;
            if (mesh.mesh == range.mesh() && mesh.offset <= range.offset() && range.offset() < mesh.offset + mesh.size) {
                return part.material;
            }
        }
        return null;
    }

    /**
     * A road coat the span carries, as a source of its cut, null for none: its triangles that {@code carried} takes,
     * copied out of the chunk's overlay mesh into a
     * mesh of their own ({@code owned}, the caller's to dispose once the cut has read it), their mask moved into
     * {@code masks}, which outlive the chunk's, and the material the chunk draws it with ({@code drawn}) on those masks,
     * one for each such material and page ({@code materials}). It still blends over the deck: the pieces draw it after
     * themselves (GpuDestruction#overlays).
     */
    private static MeepleShatter.Source coatSource(TileRange range, Material drawn, GpuTextures<BoardScene.Pixels> masks,
          Matrix4 frame, Map<List<Object>, Material> materials, List<Mesh> owned, Predicate<Vector3[]> carried) {
        var mask = range.material().get(GpuRoads.Mask.class, GpuRoads.Mask.TYPE);
        var region = masks.region(mask.data.pixels());
        var points = new com.badlogic.gdx.utils.FloatArray();
        var indices = kept(points, copied(range, points), range.mesh().getVertexAttributes(), carried);
        if (indices.isEmpty()) { return null; }
        float[] vertices = points.toArray();
        var attributes = range.mesh().getVertexAttributes();
        GpuRoads.relocate(vertices, new GpuRoads.Mask(region, mask.data), attributes);
        var copy = new Mesh(Mesh.VertexDataType.VertexArray, false, vertices.length / (attributes.vertexSize / Float.BYTES),
              indices.size, attributes);
        copy.setVertices(vertices);
        copy.setIndices(indices.toArray());
        owned.add(copy);
        Texture page = region.getTexture();
        Texture chunkPage = mask.textureDescription.texture;
        Material material = materials.computeIfAbsent(List.of(drawn, page), key -> {
            var moved = new Material(drawn);
            // The chunk's mask page also stands in as a merged coat's diffuse texture, which gives it its coordinates.
            for (Attribute attribute : drawn) {
                if (attribute instanceof TextureAttribute texture && texture.textureDescription.texture == chunkPage) {
                    var onPage = (TextureAttribute) texture.copy();
                    onPage.textureDescription.texture = page;
                    moved.set(onPage);
                }
            }
            return moved;
        });
        return new MeepleShatter.Source(new com.badlogic.gdx.graphics.g3d.model.MeshPart("coat", copy, 0, indices.size,
              GL20.GL_TRIANGLES), material, frame, 0);
    }

    /** The drawn parts of {@code node} and its children, as sources of the cut in its frame. */
    private static void addSources(Node node, ModelInstance instance, Matrix4 frame, Map<Material, Material> copies,
          List<MeepleShatter.Source> sources) {
        for (NodePart part : node.parts) {
            // Glass is what blends in the model itself: a building faded around its occupants or the pointer still breaks.
            Material own = instance.model.getMaterial(part.material.id);
            if (!part.enabled || part.meshPart.primitiveType != GL20.GL_TRIANGLES || part.meshPart.size == 0
                  || GpuTerrain.blended(own != null ? own : part.material)) { continue; }
            var boardUV = part.material.get(GpuModelMaterial.class, GpuModelMaterial.TYPE);
            sources.add(new MeepleShatter.Source(part.meshPart, copies.computeIfAbsent(part.material,
                        TerrainFalls::pieceMaterial),
                  new Matrix4(frame).mul(instance.transform).mul(node.globalTransform), boardUV == null ? 0 : boardUV.boardUV));
        }
        for (Node child : node.getChildren()) { addSources(child, instance, frame, copies, sources); }
    }

    /**
     * A piece's copy of a drawn material: solid, whatever fade its building wore; drawn from both sides, as
     * a shell open below (a kit storey's floor belongs to its interior) shows its inside when its foot topples over; and
     * on its mesh's UVs, which carry its board UVs once cut.
     */
    private static Material pieceMaterial(Material drawn) {
        var copy = new Material(drawn);
        GpuTerrain.opaque(copy);
        copy.set(IntAttribute.createCullFace(GL20.GL_NONE));
        var surface = copy.get(GpuModelMaterial.class, GpuModelMaterial.TYPE);
        if (surface != null && surface.boardUV != 0) { copy.set(surface.ownUVs()); }
        return copy;
    }

    /**
     * The spans a transition brought down that the terrain now shows gone fall from the frame it does, as nodes of a
     * model of their own (GpuDestruction#takeModel), down to the hex's ground, its bed under water. A span whose cut is
     * not in, or failed, simply goes.
     */
    void startSpans() {
        for (var falling = spansFalling.entrySet().iterator(); falling.hasNext(); ) {
            var entry = falling.next();
            Coords at = entry.getKey();
            var tile = terrain.coverScene.tile(at);
            if (tile != null && BoardScene.TileTransition.structure(tile)) { continue; }
            falling.remove();
            Span span = spans.remove(at);
            if (span != null) { takeSpan(entry.getValue(), span); }
        }
    }

    private void takeSpan(BoardScene.TileTransition.Collapse change, Span span) {
        MeepleShatter.Cut cut = span.cut().isDone() && !span.cut().isCompletedExceptionally() ? span.cut().join() : null;
        if (cut == null || cut.pieces() == 0) {
            span.dispose();
            return;
        }
        Model model = MeepleShatter.model("span", cut);
        var bounds = model.calculateBoundingBox(new BoundingBox());
        var before = change.before();
        // Its pieces come down onto the hex's ground, onto its bed under water.
        float floor = span.water() ? BoardGeometry.groundZ(before) + .5f : BoardGeometry.surfaceZ(before);
        bounds.min.z = Math.min(floor, bounds.max.z - 1);
        var middle = bounds.getCenter(new Vector3());
        var push = new Vector2();
        int flags = change.loads().isEmpty() ? 0 : GpuDestruction.LOADED;
        if (change.from() != null && change.strength() > 0) {
            var from = BoardGeometry.center(change.from().coords(), change.from().elevation());
            if (from.z > bounds.max.z) { flags |= GpuDestruction.LOADED; }
            else { push.set(middle.x - from.x, middle.y - from.y).nor().scl(change.strength()); }
        }
        // Its dust rises from where it stood, its spray from the water's surface.
        float dust = span.water() ? BoardGeometry.waterZ(before) : middle.z;
        destruction.takeModel(change.coords(), model, span.masks(), bounds, middle.x, middle.y, push, flags, span.water(), dust);
        destruction.start(change.coords(), 0);
    }

    /**
     * A transition's spans still being cut, its structures still waiting for the reads of their stored cuts
     * (#presentFalls), or falls taken that wait for their pieces (#supplyFalls): the terrain keeps them installed until
     * they are taken or have their pieces. A span is not kept on disk (its road coats' masks, its carried routes), so it
     * is cut as its fall is named, on the cutter's threads.
     */
    boolean cutting() {
        // A fall taken before its pieces were made (#supplyFalls) holds them from its hex's installed mesh.
        return fallsWaiting || destruction.hasPending()
              || spansFalling.keySet().stream().anyMatch(at -> spans.containsKey(at) && !spans.get(at).cut().isDone());
    }

    /** The dust of every structure coming down, which the attack effects draw. */
    List<java.util.function.Consumer<GpuExplosionEffects>> dust() {
        return List.of(destruction::dust);
    }

    /** Ends every collapse at once, as Instant playback does: the terrain shows what replaced them. */
    void clearCollapses() {
        disposeSpans();
        fallsFor = null;
        fallsWaiting = false;
        // What the installed scene still shows standing (a strike's buildings the rules bring down at the phase's end, or
        // its trees) goes back into its chunk.
        Set<Chunk> standing = new LinkedHashSet<>();
        for (Coords at : destruction.taken()) {
            Chunk chunk = terrain.coverScene == null ? null : terrain.chunkOf(at);
            if (chunk != null) { standing.add(chunk); }
        }
        destruction.clear();
        // Nothing falls and nothing is about to: no hex holds a cut, and none is under way.
        cuts.clear();
        fallsPreparing.clear();
        if (!standing.isEmpty()) {
            standing.forEach(terrain::refall);
            terrain.refreshShadows();
        }
        terrain.stampFalls();
    }

    void disposeSpans() {
        standings.values().forEach(standing -> standing.future().cancel(false));
        standings.clear();
        spans.values().forEach(Span::dispose);
        spans.clear();
        spansFalling.clear();
    }
}
