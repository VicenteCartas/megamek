/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.RenderableProvider;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.model.NodePart;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.IntArray;
import megamek.common.board.Coords;

/**
 * Structures falling where the board drew them. A falling hex's kit pieces leave its chunk for lists of the GPU-driven
 * path here (GpuIndirectInstances), whose instanced programs pose every piece on the GPU (tree-instances.glsl, fallBegin) from the hex's slot: three texels in the local
 * lights' texture ({@link GpuLavaLighting#setFalls}) holding when it gives way, what pushes it, where it stands and when
 * it sinks. In the air no piece is simulated or uploaded per frame, so a city falls in the draws its standing buildings
 * took.
 * <p>
 * Where a fall shows large enough ({@link #take} with {@code contact}), its pieces land as bodies: each has a pose row
 * ({@link GpuPoses}), which names the fall's slot while the piece flies in closed form; as it touches down it is handed
 * to a box body of the fall's {@link MeepleDebris}, thrown as the closed form has it then ({@link FallForm}), and its
 * row is written from that body until it lies still. Bodies are admitted while their stepping stays within
 * {@link #STEP_NANOS} a frame; a piece not admitted, and every piece of any other fall, settles in closed form.
 * <p>
 * A bridge's span is part of its chunk's terrain meshes, whose programs have no instanced variant: it falls as the nodes
 * of a model of its own ({@link #takeModel}), each node posed every frame from the same closed form evaluated here
 * ({@link FallForm#node}), or from its box body once it has touched down. Render thread only; presentation, never game
 * state: the rules decide what falls and the terrain installs the rubble.
 */
final class GpuDestruction implements Disposable {
    /** Texels a slot takes: when and how it falls, where it stands, when it sinks (tree-instances.glsl). */
    static final int TEXELS = 3;
    /** A slot's flag: a unit's weight brings it down, driving the pieces under it down and giving way sooner. */
    static final int LOADED = 1;
    /** A slot's flag: a blast's front brings it down, piece by piece as it passes (#blastFrom). */
    static final int BLAST = 2;
    /** A slot's flag: it bursts, a fuel tank in its own explosion (#burst) or trees an attack breaks (#breakTrees). */
    static final int BURST = 4;
    /**
     * The collapse's physics, in playback seconds and world units: the crushed foot bursts out this fast, the storeys
     * above spill out and tumble up to these rates and topple outward; a hit at full strength pushes and topples them
     * away from it; a unit's weight drives them down.
     */
    static final float CRUSH = 30, SPILL = 16, TUMBLE = 4, TOPPLE_OUT = 2.5f, PUSH = 40, TOPPLE = 4, LOAD_DROP = 40;
    /** A burst's fastest launch, in world units per playback second (#burst). */
    static final float BURST_SPEED = 200;
    /**
     * As fast as a mek's death (MeepleCasualties): the top gives way at most this long after the foot, and every piece
     * comes down within FALL_SECONDS whatever the structure's height (gravity grows with the drop); a pile is at most
     * PILE_LEVELS high.
     */
    static final float RELEASE_SPAN = .25f, FALL_SECONDS = .55f, PILE_LEVELS = 1;
    /** Standard gravity on the table, and how fast a piece on the ground loses its speed and its spin, per second. */
    static final float GRAVITY = 450, GROUND_DRAG = 24, GROUND_SPIN_DRAG = 16;
    /** A pile rests this long once the terrain shows the rubble under it, then sinks into it over SINK_SECONDS. */
    static final float REST_SECONDS = .75f, SINK_SECONDS = .6f;
    /**
     * The rubble the rules leave rises out of the ground (#rubble) from RISE_DELAY after its hex gives way, over
     * RISE_SECONDS: under the dust at the structure's foot, 1.2 to 1.8 of the player's seconds in at the 1x speed
     * (user, 2026-11-14), and before its pile sinks ({@link #settles}, REST_SECONDS).
     */
    static final float RISE_DELAY = .6f, RISE_SECONDS = .3f;
    /**
     * A piece thrown past the board's edge shrinks to nothing this long after passing its floor's height: here, and the
     * cut pieces of MeepleDebris.
     */
    static final float EDGE_SECONDS = .6f;
    /**
     * A debris instance with pose rows names its first row plus this (a_instance1.w, tree-instances.glsl); every other
     * names its hex's slot + 1, which stays far below it.
     */
    static final int ROW_OWNER = 1 << 20;
    /**
     * The bodies' steps take at most about this long a frame: a piece is handed to a body only while the bodies awake,
     * those handed over in the frame included, are expected to step within it ({@link #land}).
     */
    static final long STEP_NANOS = 1_500_000L;
    /**
     * Pose rows go to the pieces that show at least this large, in scene pixels, the largest first, and at most
     * {@link #ROW_BUDGET} rows are held by the falls at once: they share them ({@link #grant}). A smaller piece, or one
     * past the budget, settles in closed form.
     */
    static final float ROW_PIXELS = 6;
    static final int ROW_BUDGET = 4096;
    /** The stepper's fixed step (MeepleDebris), for the expected cost of a frame's steps. */
    private static final float BODY_STEP = 1 / 120f;
    /**
     * The stepper is run on by at most this long a call: it takes a tenth of a second at most (MeepleDebris#advance),
     * with the part of a step it still owes.
     */
    private static final float STEP_SECONDS = .09f;
    /** A body still moving this long after its fall's pieces have settled in closed form ({@link #settles}) is laid to rest. */
    private static final float BODY_SECONDS = .4f;
    /** A time that never comes: standing, or not sinking. */
    private static final float NEVER = 1e9f;
    /** The longest a piece falls before its pile rests, as at zero gravity, where it would drift on. */
    private static final float LONGEST_FALL = 6;
    /** A felled tree has come down and rocked to rest this long after its front reached it (tree-instances.glsl). */
    private static final float TREE_FALL_SECONDS = 1.3f;
    /** The level of detail felled trees cast their shadows at. */
    private static final int SHADOW_LEVEL = 1;
    /**
     * A cloud of dust lasts this long, in playback seconds, which at the 1x speed are two of the player's each
     * (UnitMotion.Speed): thick for its first quarter, then thinning away.
     */
    static final float CLOUD_SECONDS = 3;
    /**
     * A structure that gives way raises one cloud with the structures on the hexes next to it that start within
     * {@link #JOIN_SECONDS} of it (#raise). At most {@link #MAX_CLOUDS} hang at once: under a strike the structures that
     * fall later raise none, rather than take a standing cloud away.
     */
    private static final int MAX_CLOUDS = 96;
    private static final float JOIN_SECONDS = 1;
    /** Pale, a little warm, concrete and plaster dust; over water, white spray. */
    static final GpuExplosionEffects.Look DUST = new GpuExplosionEffects.Look(-1, 0, .15f),
          SPRAY = new GpuExplosionEffects.Look(-1, 0, 0);
    /**
     * A span's hard landings raise a puff of dust, or spray over water, for this long, in playback seconds; at most
     * {@link #PUFFS} of them, shared among the spans falling at once: the explosion volumes are every effect's.
     */
    private static final float PUFF_SECONDS = 1.4f;
    private static final int PUFFS = 16;

    /** The fall's numbers as GLSL constants, so that the shader and {@link #settles} agree. */
    static String defines() {
        return "#define FALL_TEXELS " + TEXELS + "\n#define FALL_LOADED " + LOADED + "\n#define FALL_BLAST " + BLAST
              + "\n#define FALL_BURST " + BURST + "\n#define FALL_BURST_SPEED " + BURST_SPEED
              + "\n#define FALL_CRUSH " + CRUSH
              + "\n#define FALL_SPILL " + SPILL + "\n#define FALL_TUMBLE " + TUMBLE + "\n#define FALL_TOPPLE_OUT " + TOPPLE_OUT
              + "\n#define FALL_PUSH " + PUSH + "\n#define FALL_TOPPLE " + TOPPLE + "\n#define FALL_LOAD_DROP " + LOAD_DROP
              + "\n#define FALL_GROUND_DRAG " + GROUND_DRAG + "\n#define FALL_GROUND_SPIN_DRAG " + GROUND_SPIN_DRAG
              + "\n#define FALL_SINK " + SINK_SECONDS + "\n#define FALL_RELEASE_SPAN " + RELEASE_SPAN
              + "\n#define FALL_SECONDS " + FALL_SECONDS + "\n#define FALL_PILE_LEVELS " + PILE_LEVELS
              + "\n#define FALL_RISE " + RISE_SECONDS + "\n#define FALL_RISE_DELAY " + RISE_DELAY
              + "\n#define FALL_EDGE " + EDGE_SECONDS + "\n#define POSE_ROW " + GpuPoses.ROW
              + "\n#define POSE_TABLE_ROW " + GpuPoses.ROWS + "\n#define POSE_UNIT " + GpuPoses.UNIT
              + "\n#define PIECE_RECORD_UNIT " + GpuIndirectInstances.RECORDS_UNIT
              + "\n#define FALL_ROW_OWNER " + ROW_OWNER + "\n";
    }

    /** A felled tree: its species, where it stands and its size (GpuTreeInstances.Stand#add). */
    record Tree(String species, Matrix4 transform, float diameter) { }

    /**
     * One falling hex: its slot, its pieces (each drawn instanced, its node's box in its mesh part), the kit assemblies and
     * meshes they borrow, and its bounds standing; or a hex's felled trees and the scene features they stood for.
     */
    private static final class Fall {
        final int slot;
        final Array<Renderable> pieces;
        final Set<GpuBuilding.Assembly> buildings;
        final List<Tree> trees;
        final Set<BoardScene.Feature> features;
        final BoundingBox bounds;
        final float[] texels = new float[TEXELS * 4];
        final Coords coords;
        /** When it gives way, when the terrain first showed the rubble under it, and when its pile sinks. */
        float start = NEVER, gone = NEVER, sinks = NEVER;
        /**
         * For a blast's front (#blastFrom): how much sooner than its start the front would have taken a piece at ground
         * zero, and how much later than it the front takes its last piece.
         */
        float lead, spread;
        /** A cloud holds its dust (#raise). */
        boolean dusty;
        /** Where the trees an attack broke stood (#breakTrees): their chunks' stands leave them out. */
        Set<Long> stood = Set.of();
        /** Its pieces' rows and bodies where they land as bodies (#take); null where they fall in closed form alone. */
        Contact contact;
        /** It shows large enough for its pieces to land as bodies and waits for its rows ({@link #grant}). */
        boolean asks;
        /** Each piece's first pose row, by its index among the pieces, -1 for none; null without rows. */
        int[] rowOf;
        /**
         * Its pieces' list (#indirect), written again where its rows change ({@code stale}), and its rubble's list,
         * written once the rubble is given (#rubble).
         */
        GpuIndirectInstances.Instances list, rubbleList;
        boolean stale;
        /**
         * Its pieces cast the shadow from the moment the first of them moves ({@link #casts}):
         * until then the structure's own shadow stays in the cached map, which is redrawn without it then.
         */
        boolean casting;
        /**
         * Taken before its pieces were made ({@link #take} with none): its structure still stands in its chunk until
         * they come ({@link #supply}), before its first piece moves. Whether its pieces are to land as bodies then.
         */
        boolean pending, contacts;
        /** Whether one of its pieces is a broken part's fragments, once found (#near); null until then. */
        Boolean fragmented;
        /** The rubble the rules left where it stood (#rubble): pieces that rise under its dust. */
        final Array<Renderable> rubble = new Array<>();
        /**
         * A fall drawn as the nodes of a model of its own (#takeModel), null for any other: its instance, which owns its
         * model; its pieces' nodes in the order of its contact's pieces; the parts that blend over them (a road's coats,
         * drawn after the solids, #overlays); what its materials sample, which it disposes; whether it falls into water,
         * the height its dust rises from, and its hard landings' puffs, five floats each (where, how large, when).
         */
        ModelInstance instance;
        Node[] nodes;
        final List<Overlay> overlays = new ArrayList<>();
        Disposable owned;
        boolean water;
        float dustZ;
        final FloatArray puffs = new FloatArray();

        Fall(Coords coords, int slot, Array<Renderable> pieces, Set<GpuBuilding.Assembly> buildings, List<Tree> trees,
              Set<BoardScene.Feature> features, BoundingBox bounds) {
            this.coords = coords;
            this.slot = slot;
            this.pieces = pieces;
            this.buildings = buildings;
            this.trees = trees;
            this.features = features;
            this.bounds = bounds;
        }
    }

    /** A part that blends over a node fall's pieces, with the renderable it is drawn through (#overlays). */
    private record Overlay(Node node, NodePart part, Renderable renderable) { }

    /**
     * A fall whose pieces land as bodies: {@code count} pieces (a part, or each fragment of a broken one), each with its
     * pose row ({@code row}), analytic until it is handed over; or, for a fall drawn as nodes ({@link #takeModel}), no
     * rows ({@code row} null), each piece a node.
     */
    private static final class Contact {
        final int count;
        final int[] row;
        /** Each piece's body, -1 while it flies. */
        final int[] bodyOf;
        /** Each piece's inputs to the closed form ({@link FallForm#INPUTS} floats). */
        final float[] inputs;
        /**
         * When each piece is handed over, on the falls' clock: as it comes down to the ground under where it lands.
         * NEVER once that moment has come, and for a piece that leaves the board. Null until the fall has its start.
         */
        float[] hands;
        /** The pieces still to hand over. */
        int flying;
        /** The pieces handed over, by body: each one's row from {@code first}. */
        final IntArray landed = new IntArray();
        /** Their bodies, under the fall's gravity ({@code pull} standard gravities); null while none has landed. */
        MeepleDebris bodies;
        float pull;
        /** The falls' clock at the bodies' own time 0. */
        float epoch;
        /** How far the rows were last written sunk, as a share (MeepleDebris#sink). */
        float sunk;

        Contact(int[] row, int count) {
            this.row = row;
            this.count = count;
            inputs = new float[count * FallForm.INPUTS];
            bodyOf = new int[count];
            java.util.Arrays.fill(bodyOf, -1);
        }
    }

    private final GpuLavaLighting lights;
    /** The GPU-driven path the pieces draw on (GpuIndirectInstances); null only where nothing draws (logic tests). */
    private final GpuIndirectInstances indirect;
    /** The slots in use: by a fall, a hex's felled trees, a break, or a fall prepared to come (#prepare). */
    private final java.util.BitSet slots = new java.util.BitSet();
    /** Whether a fall shows large enough for bodies or fragments: its pieces' shadows follow every frame (#publish). */
    private boolean nearFalls;
    /** A run of pieces' record (GpuIndirectInstances#pieces): its box, and its fragments' table among the pose rows. */
    private final GpuIndirectInstances.Pieces records = new GpuIndirectInstances.Pieces() {
        @Override
        public float[] record(Renderable sample) {
            float[] piece = GpuInstancedMesh.piece(sample);
            // A table that found no room among the table rows: the part falls whole.
            int table = piece.length > GpuInstancedMesh.PIECE ? poses.table(fragments(sample)) : -1;
            float broken = table < 0 ? 0 : (piece.length - GpuInstancedMesh.PIECE) / 8f;
            // The tenth float takes the run's first vertex; the eleventh keeps where the table went, for its release.
            return new float[] { piece[0], piece[1], piece[2], piece[6], piece[3], piece[4], piece[5], broken,
                  Math.max(table, 0), 0, table, 0 };
        }

        @Override
        public void released(Renderable sample, float[] record) {
            if (record[10] >= 0) { poses.release(fragments(sample)); }
        }

        private float[] fragments(Renderable sample) { return ((GpuInstancedMesh.Fragments) sample.userData).table(); }
    };
    private final Map<Coords, Fall> falls = new LinkedHashMap<>();
    /** The hexes whose trees a blast fells, by hex: they leave their chunks' stands for {@link #fallen}. */
    private final Map<Coords, Fall> groves = new LinkedHashMap<>();
    /** The trees attacks broke, each break a slot of its own: a volley's next one at a hex never cuts the last short. */
    private final List<Fall> shattered = new ArrayList<>();
    /** Every felled tree, its instance naming its hex's slot + 1, and where each stood (chunks leave those out). */
    private final GpuTreeInstances.Stand fallen = new GpuTreeInstances.Stand();
    /** The felled trees as GPU-driven lists: at their own levels, at the shadow map's, and their fading parts. */
    private GpuIndirectInstances.Instances fallenList, fallenShadows, fallenFades;
    /** Whether the groves came or went since {@link #fallen} was filled (#publish): only then is it filled again. */
    private boolean grovesChanged;
    private final Set<Long> felled = new java.util.HashSet<>();
    /** The felled trees' models and lists (GpuTreeInstances#list). */
    private final GpuTreeInstances fallenTrees;
    /**
     * The pieces of the falls to come, by hex ({@link #prepare}), each with its slot and its pieces' list, written ahead
     * of the frame that takes them, a few a frame ({@link #build}), and kept until their fall has come or is forgotten:
     * the frame that takes one writes nothing ({@code unwritten}: those still to write).
     */
    private final Map<Coords, Prepared> prepared = new java.util.HashMap<>();
    private final java.util.ArrayDeque<Coords> unwritten = new java.util.ArrayDeque<>();

    /** A fall to come: its pieces as made from {@code made} (its hex's mesh), and its list. */
    private static final class Prepared {
        final Array<Renderable> pieces;
        final Object made;
        int slot = -1;
        GpuIndirectInstances.Instances list;

        Prepared(Array<Renderable> pieces, Object made) {
            this.pieces = pieces;
            this.made = made;
        }
    }
    private float[] texels = new float[0];
    private boolean changed;
    /** Counts the changes of which hexes are taken: a chunk snapshot of another count may draw them wrongly. */
    private int revision;
    private float clock, gravity = GRAVITY;
    private final List<Cloud> clouds = new ArrayList<>();
    /** The rows of the pieces that land as bodies. */
    private final GpuPoses poses = new GpuPoses();
    /** The board's hexes' floors, which the bodies land on ({@link #floors}); null until the terrain gives them. */
    private MeepleDebris.Floors floors;
    private final FallForm form = new FallForm();
    private final float[] pose = new float[8];
    private final FloatArray placed = new FloatArray(GpuTreeInstances.STRIDE);
    /**
     * What the bodies' steps took in the last frame, the most a frame's may take (tests set it below nothing to admit
     * none), and what one awake body's step has taken lately, which admission expects.
     */
    private long stepNanos;
    long stepBudget = STEP_NANOS;
    private float bodyStepNanos = 300;
    /** The frame's camera: a fall's pieces get rows by how large they show in it ({@link #grant}); null shows all. */
    private com.badlogic.gdx.graphics.Camera view;
    /** The pieces handed to bodies so far, and the bodies not at rest after the last frame. */
    private int admitted, awake;

    /**
     * The dust over a fall and those next to it (#raise), from the first of them on. It outlives their piles: a pile has
     * sunk long before its dust has cleared.
     */
    private static final class Cloud {
        final float x, y, floor, height, reach;
        final int seed;
        float seconds;

        Cloud(float x, float y, float floor, float height, float reach, int seed) {
            this.x = x;
            this.y = y;
            this.floor = floor;
            this.height = height;
            this.reach = reach;
            this.seed = seed;
        }
    }
    /** The board's size in hexes: past its edge nothing lands (tree-instances.glsl). */
    private int columns, rows;

    /**
     * {@code trees}: loads a tree model at a level by asset name, as the terrain's stands do; {@code indirect}: the
     * GPU-driven path the pieces draw on (#addLists).
     */
    GpuDestruction(GpuLavaLighting lights, BiFunction<String, Integer, Model> trees, GpuIndirectInstances indirect) {
        this.lights = lights;
        this.indirect = indirect;
        fallenTrees = new GpuTreeInstances(trees, true);
    }

    /**
     * Where the shadow map's casters changed since the last call: the piles and trees taken, and the structures whose
     * pieces began to cast ({@link #casts}). Their chunks' cached shadow changed there alone.
     */
    BoundingBox takenSince() {
        // Not a copy: a copy of an empty box orders its corners, and is all of space.
        BoundingBox result = new BoundingBox().inf();
        if (takenRegion.isValid()) { result.set(takenRegion); }
        takenRegion.inf();
        return result;
    }

    private final BoundingBox takenRegion = new BoundingBox().inf();

    /**
     * When the falling pieces' shadows are drawn again (GpuTerrain's shadow caster revision): every frame the clock moves
     * while a fall shows large enough for bodies or fragments, else at most {@link #FAR_SHADOW_RATE} times a playback
     * second: a far fall's pieces move a pixel or two a frame, and a city's 91,670 parts cost the shadow pass each draw.
     */
    int shadowRevision() {
        publish();
        return nearFalls || !shattered.isEmpty() ? Float.floatToIntBits(clock) : (int) (clock * FAR_SHADOW_RATE);
    }

    /** Whether a fall shows large enough for bodies or fragments ({@link #shadowRevision}): found when the falls change. */
    private boolean near() {
        for (Fall fall : falls.values()) {
            if (fall.contact != null || fall.asks) { return true; }
            if (fall.fragmented == null) {
                fall.fragmented = false;
                for (Renderable piece : fall.pieces) {
                    if (piece.userData instanceof GpuInstancedMesh.Fragments) { fall.fragmented = true; break; }
                }
            }
            if (fall.fragmented) { return true; }
        }
        return false;
    }

    private static final float FAR_SHADOW_RATE = 15;

    /** The falls' clock: playback seconds, which stop while it is paused and follow its speed. */
    float clock() { return clock; }

    /** Whether the hex's structure has left its chunk for its pieces here: its chunk must not draw it. */
    boolean taken(Coords at) { return falls.containsKey(at); }

    /** Which hexes are taken, as a count of their changes: a chunk snapshot taken at another count is stale. */
    int revision() { return revision; }

    /** Whether a tree standing at this transform has been felled: its chunk's stand must not draw it. */
    boolean felled(Matrix4 transform) { return felled.contains(spot(transform)); }

    private static long spot(Matrix4 transform) {
        return (long) Float.floatToIntBits(transform.val[Matrix4.M03]) << 32
              | Float.floatToIntBits(transform.val[Matrix4.M13]) & 0xffffffffL;
    }

    /** Whether the hex's structure has begun to give way: nothing may pick it any more. */
    boolean started(Coords at) {
        Fall fall = falls.get(at);
        return fall != null && clock >= fall.start;
    }

    boolean isEmpty() { return falls.isEmpty() && groves.isEmpty() && shattered.isEmpty(); }

    /** The falls drawn as pieces: the structures' and the trees attacks broke. */
    private List<Fall> pieceFalls() {
        List<Fall> result = new ArrayList<>(falls.values());
        result.addAll(shattered);
        return result;
    }

    /** How many hexes' trees are felled, falling or lying. */
    int groveCount() { return groves.size(); }

    /** How many parts fall, and how many of them in fragments (KitFragments). */
    int[] parts() {
        int broken = 0, all = 0;
        for (Fall fall : pieceFalls()) {
            // A span's pieces are its cut's fragments.
            all += fall.nodes == null ? 0 : fall.nodes.length;
            broken += fall.nodes == null ? 0 : fall.nodes.length;
            for (Renderable piece : fall.pieces) {
                if (piece.userData == GpuInstancedMesh.RUBBLE) { continue; }
                all++;
                if (piece.userData instanceof GpuInstancedMesh.Fragments) { broken++; }
            }
        }
        return new int[] { all, broken };
    }

    /**
     * Takes the hex's structure from its chunk: {@code pieces} stand where it stood until {@link #start}. {@code floor}
     * and {@code top} bound it, around its hex's middle ({@code middleX}, {@code middleY}); {@code push} is the hit's
     * direction times its strength (0 to 1, up to 4 for a blast beside its ground zero), zero for its own weight.
     * {@code contact}: the hex shows large enough for its pieces to land as bodies, while pose rows are left.
     * {@code pieces} null: they are not made yet; the structure stands in
     * its chunk until {@link #supply} brings them, which must come before its first piece moves ({@link #due}).
     */
    void take(Coords at, Array<Renderable> pieces, Set<GpuBuilding.Assembly> buildings, BoundingBox bounds,
          float middleX, float middleY, Vector2 push, int flags, boolean contact) {
        // The pieces prepared for it (#preparedPieces), with their slot and list where those were written ahead.
        Prepared ready = prepared.remove(at);
        boolean listed = ready != null && ready.pieces == pieces && ready.list != null;
        if (ready != null && !listed) { release(ready); }
        Fall previous = falls.remove(at);
        if (previous != null) { release(previous); }
        // A hex taken again may change what is shown or cast there too.
        revision++;
        int slot = listed ? ready.slot : takeSlot();
        Fall fall = new Fall(at, slot, pieces == null ? new Array<>() : pieces, buildings, List.of(), Set.of(),
              new BoundingBox(bounds));
        if (listed) { fall.list = ready.list; }
        fall.pending = pieces == null;
        fall.contacts = contact;
        // Its standing shadow stays until its pieces cast (#casts).
        site(fall, bounds, middleX, middleY, push, flags);
        // Its rows come with those of the other falls taken before the frame draws (#grant), once it has pieces.
        fall.asks = contact && !fall.pending;
        falls.put(at, fall);
        write(fall);
        changed = true;
    }

    /**
     * The pieces of the hex's pending fall ({@link #take} with none): from now on they are drawn and its structure no
     * longer in its chunk. Nothing for a hex taken with its pieces, or not taken.
     */
    void supply(Coords at, Array<Renderable> pieces) {
        Fall fall = falls.get(at);
        if (fall == null || !fall.pending) { return; }
        fall.pending = false;
        fall.pieces.addAll(pieces);
        fall.fragmented = null;
        fall.asks = fall.contacts;
        // The hexes shown as pieces changed: chunk lists hide this one now.
        revision++;
        changed = true;
    }

    /** Whether a fall waits for its pieces ({@link #supply}). */
    boolean hasPending() {
        for (Fall fall : falls.values()) {
            if (fall.pending) { return true; }
        }
        return false;
    }

    /** The hexes whose falls wait for their pieces ({@link #supply}), the one whose first piece moves soonest first. */
    List<Coords> pending() {
        List<Fall> waiting = new ArrayList<>();
        for (Fall fall : falls.values()) {
            if (fall.pending) { waiting.add(fall); }
        }
        waiting.sort(java.util.Comparator.comparingDouble(fall -> fall.start - fall.spread));
        List<Coords> result = new ArrayList<>(waiting.size());
        for (Fall fall : waiting) { result.add(fall.coords); }
        return result;
    }

    /** Whether the hex's pending fall has its first piece move within {@code seconds} of playback: it needs its pieces. */
    boolean due(Coords at, float seconds) {
        Fall fall = falls.get(at);
        return fall != null && fall.pending && clock + seconds >= fall.start - fall.spread;
    }

    /** Whether the hex's structure lies as pieces, drawn as such: taken, and not waiting for its pieces. */
    boolean shown(Coords at) {
        Fall fall = falls.get(at);
        return fall != null && !fall.pending;
    }

    /** The hexes whose structures are drawn as pieces ({@link #shown}). */
    Set<Coords> shown() {
        Set<Coords> result = new HashSet<>();
        falls.forEach((at, fall) -> {
            if (!fall.pending) { result.add(at); }
        });
        return result;
    }

    /**
     * Takes the hex's structure as the nodes of {@code model}, whose root stands at its cut's origin and whose children
     * are its pieces (MeepleShatter#model): a bridge's span, which the terrain no longer draws. It owns the model and
     * {@code owned} (what its materials sample) from now on. As {@link #take}, it stands until {@link #start}, then
     * falls in closed form and lands as box bodies, onto {@code bounds}' floor; into water ({@code water}) it throws
     * spray, not dust, from the height {@code dustZ}. Its blended parts draw after the solids ({@link #overlays}).
     */
    void takeModel(Coords at, Model model, Disposable owned, BoundingBox bounds, float middleX, float middleY, Vector2 push,
          int flags, boolean water, float dustZ) {
        Prepared ready = prepared.remove(at);
        if (ready != null) { release(ready); }
        Fall previous = falls.remove(at);
        if (previous != null) { release(previous); }
        // A hex taken again may change what is shown or cast there too.
        revision++;
        Fall fall = new Fall(at, takeSlot(), new Array<>(), Set.of(), List.of(), Set.of(), new BoundingBox(bounds));
        takenRegion.ext(bounds);
        site(fall, bounds, middleX, middleY, push, flags);
        fall.instance = new ModelInstance(model);
        fall.owned = owned;
        fall.water = water;
        fall.dustZ = dustZ;
        Node root = fall.instance.nodes.first();
        fall.nodes = new Node[root.getChildCount()];
        Contact contact = new Contact(null, fall.nodes.length);
        var box = new BoundingBox();
        var corner = new Vector3();
        var half = new Vector3();
        for (int piece = 0; piece < fall.nodes.length; piece++) {
            Node node = root.getChild(piece);
            node.isAnimated = true;
            fall.nodes[piece] = node;
            box.inf();
            for (NodePart part : node.parts) {
                box.ext(corner.set(part.meshPart.center).sub(part.meshPart.halfExtents));
                box.ext(corner.set(part.meshPart.center).add(part.meshPart.halfExtents));
                if (GpuTerrain.blended(part.material)) {
                    part.enabled = false;
                    fall.overlays.add(new Overlay(node, part, new Renderable()));
                }
            }
            // Its placement: the cut's origin, unturned and unscaled; its box, about which it turns.
            int row = piece * FallForm.INPUTS;
            contact.inputs[row] = root.translation.x;
            contact.inputs[row + 1] = root.translation.y;
            contact.inputs[row + 2] = root.translation.z;
            contact.inputs[row + 3] = 1;
            contact.inputs[row + 4] = 1;
            contact.inputs[row + 6] = 1;
            FallForm.box(contact.inputs, row + GpuTreeInstances.STRIDE, null, 0, box.getCenter(corner),
                  box.getDimensions(half).scl(.5f));
        }
        fall.contact = contact;
        falls.put(at, fall);
        write(fall);
        changed = true;
    }

    /**
     * Frees what a fall holds, on every path one leaves by: its slot, its lists, its pose rows; drawn as nodes, its
     * model and what its materials sample.
     */
    private void release(Fall fall) {
        slots.clear(fall.slot);
        GpuIndirectInstances.release(fall.list);
        GpuIndirectInstances.release(fall.rubbleList);
        fall.list = fall.rubbleList = null;
        if (fall.rowOf != null) {
            for (int piece = 0; piece < fall.rowOf.length; piece++) {
                if (fall.rowOf[piece] >= 0) { poses.free(fall.rowOf[piece], rows(fall.pieces.get(piece))); }
            }
            fall.rowOf = null;
        }
        if (fall.instance == null) { return; }
        fall.instance.model.dispose();
        if (fall.owned != null) { fall.owned.dispose(); }
        fall.instance = null;
    }

    /** The pose rows a piece takes: one a fragment of a broken part, one for a whole part, none for rubble. */
    private static int rows(Renderable piece) {
        return piece.userData == GpuInstancedMesh.RUBBLE ? 0
              : piece.userData instanceof GpuInstancedMesh.Fragments broken ? broken.table().length / 8 : 1;
    }

    /** The frame's camera, which sizes the pieces of the falls taken in it ({@link #grant}). */
    void view(com.badlogic.gdx.graphics.Camera camera) { view = camera; }

    /**
     * Gives the falls taken since the last call their pose rows, all of them together: their parts ranked by how large
     * a piece of each shows in the frame's view, the largest first; a part showing less than {@link #ROW_PIXELS}, or
     * past what is left of {@link #ROW_BUDGET} or of the rows, gets none and settles in closed form. A part's fragments
     * take a run of rows, one each (tree-instances.glsl), each analytic until its piece is handed over.
     */
    private void grant() {
        List<Fall> asking = new ArrayList<>();
        for (Fall fall : falls.values()) {
            if (fall.asks) { asking.add(fall); }
        }
        if (asking.isEmpty()) { return; }
        int held = poses.taken();
        // Each part asking: its fall, its index there and how large its pieces show, as a sortable key.
        List<long[]> parts = new ArrayList<>();
        var middle = new Vector3();
        for (int index = 0; index < asking.size(); index++) {
            Fall fall = asking.get(index);
            fall.asks = false;
            fall.rowOf = new int[fall.pieces.size];
            java.util.Arrays.fill(fall.rowOf, -1);
            for (int piece = 0; piece < fall.pieces.size; piece++) {
                Renderable part = fall.pieces.get(piece);
                if (rows(part) == 0) { continue; }
                float shows = shows(part, middle);
                if (shows >= ROW_PIXELS) {
                    parts.add(new long[] { Float.floatToIntBits(shows), index, piece });
                }
            }
        }
        parts.sort((a, b) -> Long.compare(b[0], a[0]));
        int left = Math.max(0, ROW_BUDGET - held);
        for (long[] part : parts) {
            Fall fall = asking.get((int) part[1]);
            int count = rows(fall.pieces.get((int) part[2]));
            if (count > left) { continue; }
            int first = poses.take(count);
            if (first < 0) { continue; }
            fall.rowOf[(int) part[2]] = first;
            left -= count;
        }
        for (Fall fall : asking) {
            fall.contact = contact(fall);
            // Its pieces now name their rows: their list is written again (#publish).
            fall.stale = fall.rowOf != null;
        }
        changed = true;
    }

    /**
     * How large one piece of the part shows in the frame's view, in scene pixels: a fragment of its cut on average, else
     * the part; 0 out of view. Without a view, as large as can be.
     */
    private float shows(Renderable part, Vector3 middle) {
        if (view == null) { return Float.MAX_VALUE; }
        part.worldTransform.getTranslation(middle);
        float scale = part.worldTransform.getScaleX(), size;
        if (part.userData instanceof GpuInstancedMesh.Fragments broken) {
            float[] table = broken.table();
            float sum = 0;
            for (int at = 0; at < table.length; at += 8) { sum += Math.max(table[at + 4], Math.max(table[at + 5], table[at + 6])); }
            size = 2 * sum / Math.max(1, table.length / 8);
        } else {
            Vector3 half = part.meshPart.halfExtents;
            size = 2 * Math.max(half.x, Math.max(half.y, half.z));
        }
        size *= Math.abs(scale);
        float reach = Math.max(size, part.meshPart.halfExtents.len() * Math.abs(scale));
        if (!view.frustum.sphereInFrustum(middle, reach)) { return 0; }
        return size * BoardCamera.pixelsPerUnit(view, middle);
    }

    /** The fall's contact from the rows its parts were given ({@link #grant}); null where none has any. */
    private Contact contact(Fall fall) {
        int count = 0;
        for (int piece = 0; piece < fall.rowOf.length; piece++) {
            if (fall.rowOf[piece] >= 0) { count += rows(fall.pieces.get(piece)); }
        }
        if (count == 0) {
            fall.rowOf = null;
            return null;
        }
        Contact contact = new Contact(new int[count], count);
        int row = 0;
        for (int index = 0; index < fall.rowOf.length; index++) {
            if (fall.rowOf[index] < 0) { continue; }
            Renderable piece = fall.pieces.get(index);
            placed.clear();
            GpuTreeInstances.append(placed, piece.worldTransform, 0);
            float[] table = piece.userData instanceof GpuInstancedMesh.Fragments broken ? broken.table() : null;
            for (int fragment = 0; fragment < rows(piece); fragment++, row++) {
                System.arraycopy(placed.items, 0, contact.inputs, row * FallForm.INPUTS, GpuTreeInstances.STRIDE);
                FallForm.box(contact.inputs, row * FallForm.INPUTS + GpuTreeInstances.STRIDE, table, fragment,
                      piece.meshPart.center, piece.meshPart.halfExtents);
                contact.row[row] = fall.rowOf[index] + fragment;
                poses.analytic(contact.row[row], fall.slot);
            }
        }
        return contact;
    }

    /** The board's hexes' floors, which the pieces that land as bodies come down on: the terrain's, as its scene has them. */
    void floors(MeepleDebris.Floors floors) { this.floors = floors; }

    /** Whether a fall's pieces land as bodies: they need the board's floors ({@link #floors}). */
    boolean lands() {
        for (Fall fall : falls.values()) {
            if (fall.contact != null) { return true; }
        }
        return false;
    }

    /**
     * The contact falls' numbers, for tests and reports: the pieces handed to bodies so far, the bodies not at rest and
     * what their steps took in the last frame, the pose rows in use and how often the rows were uploaded.
     */
    record Contacts(int admitted, int awake, long stepNanos, int rows, int uploads) { }

    Contacts contacts() {
        grant();
        return new Contacts(admitted, awake, stepNanos, poses.taken(), poses.uploads());
    }

    /** The pose rows, for tests. */
    GpuPoses poses() { return poses; }

    /**
     * Uploads the pose rows a frame changed, before its passes; and from the board's first frame on, so that the
     * instanced programs never draw without them: a driver that first finds a texture on a program's sampler in the
     * frame that takes a fall prepares the program again there (15 to 50 ms on an RTX 4070).
     */
    void bindRows() { poses.bind(); }

    /**
     * The hex's fall to come will be taken with {@code pieces}, as {@link #take} gets them: its list is written from now
     * ({@link #build}) instead of in the frame that takes it. Replaces what the hex had prepared, as when its cuts came in.
     */
    void prepare(Coords at, Array<Renderable> pieces, Object made) {
        Prepared before = prepared.put(at, new Prepared(pieces, made));
        if (before != null) { release(before); }
        unwritten.add(at);
    }

    /**
     * The pieces prepared for the hex's fall to come where they were made from {@code made}, its hex's mesh as it is
     * now, and are ready to draw (their list written): the same as it would fall in now, unless
     * a cut came in since (the caller knows); else null, and the take makes its pieces, whole where a part is not ready.
     */
    Array<Renderable> preparedPieces(Coords at, Object made) {
        Prepared ready = prepared.get(at);
        return ready != null && ready.made == made && !building(at) ? ready.pieces : null;
    }

    /** Writes the prepared falls' lists until {@code deadline} (System#nanoTime), and says whether none is left. */
    boolean build(long deadline) {
        while (!unwritten.isEmpty() && System.nanoTime() < deadline) {
            Prepared ready = prepared.get(unwritten.poll());
            if (ready == null || ready.list != null) { continue; }
            // Its slot from now: its instances name it, and no fall may take it meanwhile.
            ready.slot = takeSlot();
            ready.list = list(null, ready, ready.pieces, null, ready.slot);
        }
        return unwritten.isEmpty();
    }

    /** Whether the hex's fall to come still waits for its list (#build). */
    boolean building(Coords at) {
        Prepared coming = prepared.get(at);
        if (coming == null || coming.list != null) { return false; }
        // Still to write: queued again if nothing is queued, so that a fall waiting for it always gets it.
        if (unwritten.isEmpty()) { unwritten.add(at); }
        return true;
    }

    /** Whether the piece's part is placed in the pools: taking it places no copy of its mesh. */
    boolean prepared(Renderable piece) { return indirect.placed(piece); }

    /** The falls to come that are still awaited ({@code awaited}) keep what they prepared; the others' go. */
    void retainPrepared(Predicate<Coords> awaited) {
        if (prepared.entrySet().removeIf(entry -> {
            boolean forgotten = !awaited.test(entry.getKey());
            if (forgotten) { release(entry.getValue()); }
            return forgotten;
        })) {
            if (prepared.isEmpty()) { unwritten.clear(); }
            changed = true;
        }
    }

    /** What a fall to come holds: its list and its slot. */
    private void release(Prepared ready) {
        GpuIndirectInstances.release(ready.list);
        ready.list = null;
        if (ready.slot >= 0) { slots.clear(ready.slot); }
        ready.slot = -1;
    }

    /**
     * Parts placed in the pools for pieces so far: for the guard that a prepared fall's frame places none (its name the
     * one GpuTerrain#fallBatchesBuilt's readers know).
     */
    int batchesBuilt() { return indirect.piecesPlaced(); }

    /**
     * A list of these pieces (GpuIndirectInstances#pieces), in place of {@code current}: each names its rows where it
     * has some (rowOf, by its index), else its hex's slot; its record says what moves it.
     */
    private GpuIndirectInstances.Instances list(GpuIndirectInstances.Instances current, Object source,
          Array<Renderable> pieces, int[] rowOf, int slot) {
        IntArray owners = new IntArray(pieces.size);
        for (int piece = 0; piece < pieces.size; piece++) {
            int row = rowOf == null || piece >= rowOf.length ? -1 : rowOf[piece];
            owners.add(row < 0 ? slot + 1 : ROW_OWNER + row);
        }
        return indirect.pieces(current, source, pieces, owners, records);
    }

    /**
     * Fells these trees of the hex, which stood for {@code features} of its scene tile: they leave their chunks' stands
     * and stand where they stood until {@link #startTrees}, then fall away along {@code push}. {@code bounds} holds them
     * standing, around the hex's middle.
     */
    void takeTrees(Coords at, List<Tree> trees, Set<BoardScene.Feature> features, BoundingBox bounds, float middleX,
          float middleY, Vector2 push) {
        Fall previous = groves.remove(at);
        if (previous != null) { release(previous); }
        Fall fall = new Fall(at, takeSlot(), new Array<>(), Set.of(), List.copyOf(trees), Set.copyOf(features),
              new BoundingBox(bounds));
        takenRegion.ext(bounds);
        site(fall, bounds, middleX, middleY, push, 0);
        groves.put(at, fall);
        grovesChanged = true;
        // Its chunk restands without them before the next publish (TerrainFalls#strike).
        for (Tree tree : fall.trees) { felled.add(spot(tree.transform())); }
        revision++;
        write(fall);
        changed = true;
    }

    private static void site(Fall fall, BoundingBox bounds, float middleX, float middleY, Vector2 push, int flags) {
        fall.texels[1] = flags;
        fall.texels[2] = push.x;
        fall.texels[3] = push.y;
        fall.texels[4] = middleX;
        fall.texels[5] = middleY;
        fall.texels[6] = bounds.min.z;
        fall.texels[7] = bounds.max.z;
    }

    /**
     * Breaks this woods cover of the hex, which an attack or a blast took down, into {@code pieces} where it stood
     * ({@code stood}, its transforms; {@code features}, its scene tile's): it leaves its chunk and bursts {@code delay}
     * playback seconds from now, thrown out from the hex's middle and along {@code push}, away from the hit, then scatters
     * and sinks as its checkpoint shows it gone (#committed). {@code bounds} holds it standing.
     */
    void breakTrees(Coords at, Array<Renderable> pieces, List<Matrix4> stood, Set<BoardScene.Feature> features,
          BoundingBox bounds, float middleX, float middleY, Vector2 push, float delay) {
        Fall fall = new Fall(at, takeSlot(), pieces, Set.of(), List.of(), Set.copyOf(features), new BoundingBox(bounds));
        takenRegion.ext(bounds);
        Set<Long> spots = new HashSet<>();
        for (Matrix4 transform : stood) { spots.add(spot(transform)); }
        fall.stood = spots;
        site(fall, bounds, middleX, middleY, push, BURST);
        shattered.add(fall);
        // Its chunk restands without them before the next publish (TerrainFalls#breakTrees).
        felled.addAll(spots);
        revision++;
        start(fall, delay);
        changed = true;
    }

    /**
     * A blast's front from ground zero ({@code x}, {@code y}) brings the hex's structure and felled trees down, taking
     * {@code slowness} playback seconds per world unit around the hex: each piece and tree goes as the front reaches it
     * and flies out from ground zero, so that a city falls in one wave behind the front, not hex by hex.
     */
    void blastFrom(Coords at, float x, float y, float slowness) {
        for (Fall fall : new Fall[] { falls.get(at), groves.get(at) }) {
            if (fall == null) { continue; }
            fall.texels[1] = (int) fall.texels[1] | BLAST;
            fall.texels[9] = x;
            fall.texels[10] = y;
            fall.texels[11] = slowness;
            // Its start is the front's at the hex's middle; its slot's, the front's at ground zero (tree-instances.glsl).
            fall.lead = slowness * Vector2.dst(x, y, fall.texels[4], fall.texels[5]);
            fall.spread = slowness * (float) Math.hypot(fall.bounds.getWidth(), fall.bounds.getHeight()) / 2;
            write(fall);
        }
    }

    /**
     * The hex's structure bursts, a fuel tank in its own explosion: every piece goes at once, thrown up and out from its
     * middle, and scatters instead of piling up (tree-instances.glsl).
     */
    void burst(Coords at) {
        Fall fall = falls.get(at);
        if (fall == null) { return; }
        fall.texels[1] = (int) fall.texels[1] | BURST;
        write(fall);
    }

    /**
     * The rubble the rules left where the hex's structure fell, scenery its chunk does not draw while the hex is taken:
     * out of sight under the ground until the dust stands at the structure's foot, then risen into place (RISE_DELAY),
     * before the pieces sink into it (tree-instances.glsl). It replaces the rubble given before.
     */
    void rubble(Coords at, Array<Renderable> pieces, BoundingBox bounds, float middleX, float middleY) {
        Fall fall = falls.get(at);
        if (fall == null) { return; }
        for (Renderable piece : pieces) { piece.userData = GpuInstancedMesh.RUBBLE; }
        fall.rubble.clear();
        fall.rubble.addAll(pieces);
        // A list of its own: the structure's pieces are not written again (a strike's whole city at once).
        GpuIndirectInstances.release(fall.rubbleList);
        fall.rubbleList = null;
        write(fall);
        changed = true;
    }

    /** The hexes whose structures lie as pieces. */
    Set<Coords> fallen() { return new HashSet<>(falls.keySet()); }

    /** The hex's structure gives way {@code delay} playback seconds from now, unless it already has. */
    void start(Coords at, float delay) { start(falls.get(at), delay); }

    /** The hex's felled trees fall {@code delay} playback seconds from now, unless they already have. */
    void startTrees(Coords at, float delay) { start(groves.get(at), delay); }

    private void start(Fall fall, float delay) {
        if (fall == null || fall.start < NEVER) { return; }
        fall.start = clock + Math.max(0, delay);
        write(fall);
    }

    /** Every structure taken and still standing gives way now. */
    void startAll() {
        for (Coords at : falls.keySet()) { start(at, 0); }
    }

    /**
     * The terrain installed a scene: every hex it no longer shows a structure on ({@code standing} false) has its pile
     * rest on the rubble once its pieces have settled, or from now, then sink into it.
     */
    void committed(Predicate<Coords> standing, BiPredicate<Coords, Set<BoardScene.Feature>> treesStanding) {
        falls.forEach((at, fall) -> {
            if (fall.gone < NEVER || standing.test(at)) { return; }
            fall.gone = clock;
            write(fall);
            // Its chunk is built anew without it: its pieces cast from now, if they did not yet (#casts).
            if (!fall.casting && !fall.pending) {
                fall.casting = true;
                castingRevision++;
                takenRegion.ext(fall.bounds);
            }
        });
        groves.forEach((at, fall) -> {
            if (fall.gone < NEVER || treesStanding.test(at, fall.features)) { return; }
            fall.gone = clock;
            write(fall);
        });
        for (Fall fall : shattered) {
            if (fall.gone < NEVER || treesStanding.test(fall.coords, fall.features)) { continue; }
            fall.gone = clock;
            write(fall);
        }
    }

    /**
     * When the hex's pieces have all come to rest: its top storey gives way last, falls its height and skids on (the
     * shader's own bound, from the same constants).
     */
    private float settles(Fall fall) {
        if (!fall.trees.isEmpty()) { return fall.start + fall.spread + TREE_FALL_SECONDS; }
        float level = Math.max(1e-3f, BoardGeometry.level()), height = Math.max(fall.bounds.getDepth(), level);
        // The shader's gravity: at least what brings the top down within FALL_SECONDS.
        float pull = Math.max(gravity, 2 * height / (FALL_SECONDS * FALL_SECONDS));
        float drop = pull > 0 ? (float) Math.sqrt(2 * height / pull) : LONGEST_FALL;
        // A burst's pieces go at once, none faster than BURST_SPEED (#burst): bounded by the highest thrown straight up.
        float flight = ((int) fall.texels[1] & BURST) != 0
              ? (BURST_SPEED + (float) Math.sqrt(BURST_SPEED * BURST_SPEED + 2 * pull * height)) / pull
              : RELEASE_SPAN * 1.2f + drop;
        return fall.start + fall.spread + Math.min(LONGEST_FALL, flight) + .3f;
    }

    /**
     * Runs the falls' clock on by {@code seconds} of playback under {@code gravity} standard gravities and retires the
     * piles that have sunk.
     */
    void advance(float seconds, float gravity, int columns, int rows) {
        clock += Math.max(0, seconds);
        this.gravity = GRAVITY * gravity;
        this.columns = columns;
        this.rows = rows;
        boolean grovesSunk = groves.values().removeIf(this::sunk);
        grovesChanged |= grovesSunk;
        if (falls.values().removeIf(this::sunk) | grovesSunk | shattered.removeIf(this::sunk)) {
            changed = true;
            revision++;
        }
        for (Cloud cloud : clouds) { cloud.seconds += Math.max(0, seconds); }
        clouds.removeIf(cloud -> cloud.seconds >= CLOUD_SECONDS);
        raise();
        land(Math.max(0, seconds));
        if (isEmpty()) { clock = 0; }
        publish();
    }

    /**
     * The structures whose first piece moves now cast as pieces from this frame on: the shadow
     * map leaves their standing parts out ({@link #casting}) and redraws its cached map where they stood
     * ({@link #takenSince}), as the wave of a strike reaches them, instead of all of a city's at its take. A piece
     * nearer the blast than its hex's middle moves up to {@code spread} sooner than the hex's start.
     */
    private void casts() {
        for (Fall fall : falls.values()) {
            if (fall.casting || fall.pending || fall.start >= NEVER || clock < fall.start - fall.spread) { continue; }
            fall.casting = true;
            castingRevision++;
            takenRegion.ext(fall.bounds);
        }
    }

    private int castingRevision;

    /**
     * Counts the changes of which structures cast as pieces ({@link #casting}): those of {@link #revision}, which
     * takes and retires them, and those of the falls that began to cast.
     */
    int castingRevision() {
        publish();
        return revision + castingRevision;
    }

    /** The hexes whose structures cast as pieces: from their first moving piece on. */
    Set<Coords> casting() {
        Set<Coords> result = new HashSet<>();
        falls.forEach((at, fall) -> {
            if (fall.casting) { result.add(at); }
        });
        return result;
    }

    /** Whether the hex's structure casts as pieces ({@link #casting}). */
    boolean casting(Coords at) {
        Fall fall = falls.get(at);
        return fall != null && fall.casting;
    }

    /** Whether the fall's pile has sunk away: what it held is freed (#release). */
    private boolean sunk(Fall fall) {
        boolean sunk = clock >= fall.sinks + SINK_SECONDS;
        if (sunk) { release(fall); }
        return sunk;
    }

    /**
     * The contact falls' frame, {@code seconds} of playback long: the pieces that come down to the ground in it are
     * handed to bodies while the bodies awake, with those handed over so far this frame, are expected to step within
     * {@link #stepBudget} (at what an awake body's step has taken lately), and the board gave floors; the others settle
     * in closed form. So the frame's steps are bounded before they run. The bodies run on by the frame's seconds,
     * however long, are laid to rest once the closed form's pieces have long settled, sink with their fall's slot, and
     * their rows follow them while they move.
     */
    private void land(float seconds) {
        grant();
        int steps = Math.min(12, Math.max(1, Math.round(seconds / BODY_STEP)));
        long room = stepBudget < 0 ? 0 : (long) (stepBudget / (bodyStepNanos * steps));
        int load = awake;
        form.board(gravity, columns, rows);
        long nanos = 0;
        int moving = 0, stepped = 0;
        for (Fall fall : falls.values()) {
            Contact contact = fall.contact;
            if (contact == null || fall.start >= NEVER) { continue; }
            if (floors == null) {
                // No floors yet: what moves as nodes moves in closed form meanwhile.
                if (fall.instance != null) { pose(fall); }
                continue;
            }
            if (contact.hands == null) { aim(fall); }
            boolean moved = false;
            for (int row = 0; contact.flying > 0 && row < contact.count; row++) {
                float hand = contact.hands[row];
                if (hand > clock) { continue; }
                contact.hands[row] = NEVER;
                contact.flying--;
                // One whose moment passed before this frame, the floors coming late, has landed in closed form.
                if (load >= room || hand <= clock - seconds) { continue; }
                load++;
                if (contact.bodies == null) {
                    contact.bodies = new MeepleDebris();
                    contact.epoch = clock - seconds;
                }
                form.start(fall.texels, contact.inputs, row * FallForm.INPUTS);
                contact.pull = form.gravity / GRAVITY;
                contact.bodyOf[row] = form.body(contact.bodies, contact.epoch + contact.bodies.seconds() - form.begins);
                contact.landed.add(row);
                admitted++;
                moved = true;
            }
            if (contact.bodies == null) {
                if (fall.instance != null) { pose(fall); }
                continue;
            }
            // While the playback stands still, so do the bodies.
            moved |= seconds > 0 && !contact.bodies.settled();
            stepped += seconds > 0 ? contact.bodies.awake() : 0;
            long from = System.nanoTime();
            float left = seconds;
            do {
                contact.bodies.advance(Math.min(left, STEP_SECONDS), floors, contact.pull);
                left -= STEP_SECONDS;
            } while (left > 0);
            if (clock >= settles(fall) + BODY_SECONDS) { contact.bodies.settle(); }
            nanos += System.nanoTime() - from;
            moving += contact.bodies.awake();
            float sinking = MathUtils.clamp((clock - fall.sinks) / SINK_SECONDS, 0, 1);
            float sunk = sinking * sinking * (3 - 2 * sinking);
            if (fall.instance != null) {
                splash(fall, contact.bodies.landings());
                contact.bodies.sink(sunk);
                contact.sunk = sunk;
                pose(fall);
                continue;
            }
            if (!moved && sunk == contact.sunk) { continue; }
            contact.sunk = sunk;
            contact.bodies.sink(sunk);
            for (int body = 0; body < contact.landed.size; body++) {
                int row = contact.landed.get(body);
                contact.bodies.pose(body, pose, 0);
                form.place(contact.inputs, row * FallForm.INPUTS);
                form.row(poses, contact.row[row], pose);
            }
        }
        stepNanos = nanos;
        awake = moving;
        // What an awake body's step took, followed slowly: one busy frame does not close admission for long.
        if (stepped > 0) {
            float measured = MathUtils.clamp(nanos / (float) (stepped * steps), 20, 20_000);
            bodyStepNanos += .2f * (measured - bodyStepNanos);
        }
    }

    /**
     * Poses every piece of a fall drawn as nodes: where its body has it once it touched down, else where the closed
     * form has it (FallForm#node), standing until it gives way.
     */
    private void pose(Fall fall) {
        Contact contact = fall.contact;
        for (int row = 0; row < contact.count; row++) {
            Node node = fall.nodes[row];
            boolean moves = form.start(fall.texels, contact.inputs, row * FallForm.INPUTS);
            if (contact.bodyOf[row] >= 0) {
                contact.bodies.pose(contact.bodyOf[row], pose, 0);
                form.pose(pose);
            } else if (!moves || !form.at(clock)) {
                node.localTransform.idt();
                continue;
            }
            form.node(node.localTransform);
        }
        fall.instance.calculateTransforms();
    }

    /** A node fall's hard landings raise puffs, at most {@link #PUFFS}. */
    private void splash(Fall fall, List<MeepleDebris.Landing> landings) {
        float reach = Math.max(1, Math.max(fall.bounds.getWidth(), fall.bounds.getHeight()) * .5f);
        for (var landing : landings) {
            if (fall.puffs.size >= 5 * PUFFS) { return; }
            fall.puffs.add(landing.point().x, landing.point().y, landing.point().z, Math.min(landing.size(), reach * .5f));
            fall.puffs.add(clock);
        }
    }

    /** Draws what blends over the node falls' pieces (a road's coats on a span), after the solids, as the terrain's coats. */
    void overlays(ModelBatch batch, Environment lighting) {
        for (Fall fall : falls.values()) {
            if (fall.instance == null || clock < fall.start) { continue; }
            for (Overlay overlay : fall.overlays) {
                Renderable renderable = fall.instance.getRenderable(overlay.renderable(), overlay.node(), overlay.part());
                renderable.environment = lighting;
                renderable.shader = null;
                batch.render(renderable);
            }
        }
    }

    /**
     * When each piece of the fall, which has its start now, is handed over: as its underside comes down to the higher
     * of the height the closed form lands it on and the floor of the hex it lands in, under the pile's mound there.
     */
    private void aim(Fall fall) {
        Contact contact = fall.contact;
        contact.hands = new float[contact.count];
        for (int row = 0; row < contact.count; row++) {
            form.start(fall.texels, contact.inputs, row * FallForm.INPUTS);
            boolean lands = form.landing < NEVER;
            contact.hands[row] = !lands ? NEVER : form.begins
                  + form.reaches(Math.max(form.ground(), floors.at(form.landsX(), form.landsY()) + form.lift()));
            if (lands) { contact.flying++; }
        }
    }

    /** The hexes whose structures lie as pieces, or whose trees were felled, instead of in their chunks. */
    Set<Coords> taken() {
        Set<Coords> result = new HashSet<>(falls.keySet());
        result.addAll(groves.keySet());
        for (Fall fall : shattered) { result.add(fall.coords); }
        return result;
    }

    /** Every fall ends at once (Instant, a skipped playback): its pieces go with the terrain's next scene. */
    void clear() {
        if (!isEmpty()) {
            changed = true;
            revision++;
        }
        if (!prepared.isEmpty()) { changed = true; }
        releaseAll();
        clouds.clear();
        clock = 0;
        publish();
    }

    /** Frees every fall, those to come included, and forgets them. */
    private void releaseAll() {
        prepared.values().forEach(this::release);
        prepared.clear();
        unwritten.clear();
        falls.values().forEach(this::release);
        groves.values().forEach(this::release);
        shattered.forEach(this::release);
        falls.clear();
        grovesChanged |= !groves.isEmpty();
        groves.clear();
        shattered.clear();
    }

    /**
     * A cloud for every structure that has just given way, over it and the structures on the hexes next to it giving
     * way with it: the dust stands where the structures stood, so a building of two wings raises none over the ground
     * between them (user, 2026-11-14), and a cloud is as wide as what fell under it.
     */
    private void raise() {
        float hex = BoardGeometry.height() * .5f;
        for (Fall first : falls.values()) {
            // A span raises its own (#dust).
            if (first.dusty || first.instance != null || clock < first.start) { continue; }
            List<Fall> members = new ArrayList<>(7);
            members.add(first);
            for (int side = 0; side < 6; side++) {
                Fall next = falls.get(first.coords.translated(side));
                if (next != null && !next.dusty && next.instance == null && next.start <= clock + JOIN_SECONDS) {
                    members.add(next);
                }
            }
            float x = 0, y = 0, floor = Float.MAX_VALUE, height = 0;
            for (Fall fall : members) {
                fall.dusty = true;
                x += fall.texels[4] / members.size();
                y += fall.texels[5] / members.size();
                floor = Math.min(floor, fall.bounds.min.z);
                height = Math.max(height, fall.bounds.getDepth());
            }
            if (clouds.size() < MAX_CLOUDS) {
                clouds.add(new Cloud(x, y, floor, height, hex * (float) Math.sqrt(members.size()), first.slot));
            }
        }
    }

    /** The dust of the structures that gave way, this frame. */
    void dust(GpuExplosionEffects effects) {
        for (Cloud cloud : clouds) {
            cloud(effects, DUST, cloud.x, cloud.y, cloud.floor, cloud.height, cloud.reach, cloud.seconds, cloud.seed, true);
        }
        int spans = 0;
        for (Fall fall : falls.values()) { spans += fall.instance != null && clock >= fall.start ? 1 : 0; }
        if (spans == 0) { return; }
        int shown = Math.max(2, Math.round(PUFFS * Math.min(1, 4f / spans)));
        var point = new Vector3();
        for (Fall fall : falls.values()) {
            if (fall.instance == null || clock < fall.start) { continue; }
            var look = fall.water ? SPRAY : DUST;
            float seconds = clock - fall.start, reach = Math.max(1, Math.max(fall.bounds.getWidth(), fall.bounds.getHeight()) * .5f);
            // A span's dust is its column alone: it has no foot to surge from.
            cloud(effects, look, fall.bounds.getCenterX(), fall.bounds.getCenterY(), fall.dustZ,
                  Math.max(1, fall.bounds.max.z - fall.dustZ), reach, seconds, fall.slot, false);
            for (int at = 0; at < Math.min(fall.puffs.size, 5 * shown); at += 5) {
                float age = (clock - fall.puffs.get(at + 4)) / PUFF_SECONDS, size = fall.puffs.get(at + 3);
                if (age >= 1) { continue; }
                point.set(fall.puffs.get(at), fall.puffs.get(at + 1), fall.puffs.get(at + 2)).add(0, 0, size * (.2f + .5f * age));
                effects.add(point, size * (.7f + .8f * age), age, 0, .5f * GpuArtilleryBlast.fadeOut(age, .3f),
                      fall.slot + 7 * at / 5, look, Vector3.Zero);
            }
        }
    }

    /**
     * The dust of a structure {@code reach} across at its foot and {@code height} tall, {@code seconds} after it gave
     * way at ({@code x}, {@code y}, {@code floor}), as one mound (GpuExplosionEffects#addCloud): tallest over the wreck,
     * where it climbs towards the structure's height while the pieces come down, as far as its foot's width allows,
     * and with {@code surges} run out along the ground, fast at first, to half as far again as the foot. A span's is
     * its column alone.
     */
    static void cloud(GpuExplosionEffects effects, GpuExplosionEffects.Look look, float x, float y, float floor,
          float height, float reach, float seconds, int seed, boolean surges) {
        float life = seconds / CLOUD_SECONDS;
        if (life >= 1) { return; }
        float spread = 1 - (float) Math.exp(-seconds * 1.35f);
        float rise = MathUtils.clamp(seconds / 1.5f, 0, 1);
        rise = rise * rise * (3 - 2 * rise);
        float column = reach * (.8f + .4f * life);
        float front = surges ? reach * (.85f + .55f * spread) : column, widest = reach * (surges ? 1.4f : 1.2f);
        float surge = Math.min(surges ? reach * (.3f + .3f * spread) : reach * .2f, height * .6f);
        // No taller than about its width: over one hex of a tall structure it is a mound, not a spire.
        float top = Math.max(surge, Math.min(height * (.3f + .45f * rise), reach * 1.2f) + reach * .4f * life);
        // Billows about a hex wide once it has spread, whatever its size; they grow with it.
        float bulb = reach / (float) Math.pow(Math.max(1, reach / (BoardGeometry.height() * .5f)), .75) * front / widest;
        // Thick while the structure comes down and its surge runs out, then thinning steadily until it has cleared.
        float thin = GpuArtilleryBlast.fadeOut(life, .25f);
        effects.addCloud(new Vector3(x, y, floor), front, surge, column, top, bulb, life,
              MathUtils.clamp(seconds / .25f, 0, 1) * thin * thin, seed, look);
    }

    /** The falling structures' and felled trees' bounds, standing; empty without any. */
    BoundingBox bounds(BoundingBox out) {
        out.inf();
        for (Fall fall : falls.values()) { out.ext(fall.bounds); }
        for (Fall fall : groves.values()) { out.ext(fall.bounds); }
        for (Fall fall : shattered) { out.ext(fall.bounds); }
        return out;
    }

    /** The felled trees' levels of detail at this scale, as the chunks' stands take theirs (never dissolving). */
    void detail(float pixelsPerUnit) { fallen.detail(pixelsPerUnit, 0, 0); }

    /** The meshes of the felled trees' parts listed so far: the pools keep their geometry (GpuIndirectInstances#retain). */
    void treeMeshes(Set<com.badlogic.gdx.graphics.Mesh> out) { fallenTrees.meshes(out); }

    /** The kit assemblies the falling pieces borrow: the terrain keeps them while any falls. */
    Set<GpuBuilding.Assembly> buildings() {
        Set<GpuBuilding.Assembly> result = TerrainFalls.assemblies();
        for (Fall fall : falls.values()) { result.addAll(fall.buildings); }
        return result;
    }

    /**
     * The falls drawn as the nodes of models of their own (a bridge's span), posed every frame, as taken and started so
     * far this frame; every other piece and the felled trees draw in the fall passes (#addLists).
     */
    RenderableProvider nodeFalls() {
        publish();
        return (out, pool) -> {
            for (Fall fall : falls.values()) {
                if (fall.instance != null) { fall.instance.getRenderables(out, pool); }
            }
        };
    }

    /**
     * The falling structures' pieces in this pass of the GPU-driven path (GpuIndirectInstances): each fall's list, and
     * its rubble's, as published (#publish); and the felled trees, the shadow map taking them at one coarse level. The
     * node falls draw through {@link #nodeFalls}.
     */
    void addLists(GpuIndirectInstances.Pass pass) {
        publish();
        for (Fall fall : falls.values()) {
            // The shadow map takes a structure's pieces from their first move on, its standing parts until then.
            if (pass != GpuIndirectInstances.Pass.FALL_SHADOW || fall.casting) { addLists(pass, fall); }
        }
        for (Fall fall : shattered) { addLists(pass, fall); }
        if (!groves.isEmpty()) {
            if (pass == GpuIndirectInstances.Pass.FALL_SHADOW) {
                indirect.add(pass, fallenShadows = fallenTrees.list(indirect, fallenShadows, fallen, SHADOW_LEVEL, false));
            } else {
                indirect.add(pass, fallenList = fallenTrees.list(indirect, fallenList, fallen, -1, false));
            }
        }
        // After the lists that placed their fragment tables.
        poses.bind();
    }

    /** The felled trees' faded texels, which they keep from standing, in the fade pass. */
    void addFades() {
        publish();
        if (!groves.isEmpty()) {
            indirect.add(GpuIndirectInstances.Pass.FALL_FADE,
                  fallenFades = fallenTrees.list(indirect, fallenFades, fallen, -1, true));
        }
        // Their slots' rows as this frame moved them, as #addLists binds them.
        poses.bind();
    }

    private void addLists(GpuIndirectInstances.Pass pass, Fall fall) {
        if (fall.list != null) { indirect.add(pass, fall.list); }
        if (fall.rubbleList != null) { indirect.add(pass, fall.rubbleList); }
    }

    /** The lowest slot no fall holds, held from now until its fall is released. */
    private int takeSlot() {
        int slot = slots.nextClearBit(0);
        slots.set(slot);
        return slot;
    }

    private void write(Fall fall) {
        // A pile sinks once it has settled and the terrain shows the rubble under it, whichever comes later.
        fall.sinks = fall.start < NEVER && fall.gone < NEVER ? Math.max(fall.gone, settles(fall)) + REST_SECONDS : NEVER;
        fall.texels[0] = fall.start < NEVER ? fall.start - fall.lead : NEVER;
        fall.texels[8] = fall.sinks;
        int end = (fall.slot + 1) * TEXELS * 4;
        if (texels.length < end) { texels = java.util.Arrays.copyOf(texels, Math.max(end, texels.length * 2)); }
        System.arraycopy(fall.texels, 0, texels, fall.slot * TEXELS * 4, TEXELS * 4);
        changed = true;
    }

    /** Hands changed slots and the clock to the shaders, and gathers the pieces again when a fall came or went. */
    private void publish() {
        grant();
        casts();
        if (changed) {
            // A fall's list is written once, from its own pieces, ahead where it was prepared; again only where its rows
            // changed. Its rubble takes a list of its own. (Logic tests draw nothing: they have no path.)
            for (Fall fall : indirect == null ? List.<Fall>of() : pieceFalls()) {
                if (!fall.pieces.isEmpty() && (fall.list == null || fall.stale)) {
                    fall.list = list(fall.list, fall, fall.pieces, fall.rowOf, fall.slot);
                }
                fall.stale = false;
                if (!fall.rubble.isEmpty() && fall.rubbleList == null) {
                    fall.rubbleList = list(null, fall.rubble, fall.rubble, null, fall.slot);
                }
            }
            nearFalls = near();
            felled.clear();
            for (Fall fall : shattered) { felled.addAll(fall.stood); }
            for (Fall fall : groves.values()) {
                for (Tree tree : fall.trees) { felled.add(spot(tree.transform())); }
            }
            // The felled trees' stand, and so their lists, change only with the groves: a strike's every other change
            // (a structure taken, rows granted, rubble) wrote them all again.
            if (grovesChanged) {
                grovesChanged = false;
                fallen.clear();
                for (Fall fall : groves.values()) {
                    for (Tree tree : fall.trees) {
                        fallen.add(tree.species(), tree.transform(), tree.diameter(), fall.slot + 1);
                    }
                }
                if (groves.isEmpty()) { releaseFallen(); }
            }
            lights.setFalls(isEmpty() ? new float[0] : texels);
            changed = false;
        }
        lights.setFallClock(clock, gravity, columns, rows, !isEmpty());
    }

    /** The felled trees' lists go once no grove is left (or with the destruction). */
    private void releaseFallen() {
        for (var list : new GpuIndirectInstances.Instances[] { fallenList, fallenShadows, fallenFades }) {
            GpuIndirectInstances.release(list);
        }
        fallenList = fallenShadows = fallenFades = null;
    }

    @Override
    public void dispose() {
        releaseAll();
        releaseFallen();
        poses.dispose();
    }
}
