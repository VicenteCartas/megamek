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

import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.RenderableProvider;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.IntArray;
import megamek.common.board.Coords;

/**
 * Structures falling where the board drew them. A falling hex's kit pieces leave its chunk for the debris batches here,
 * which pose every piece on the GPU (tree-instances.glsl, fallBegin) from the hex's slot: three texels in the local
 * lights' texture ({@link GpuLavaLighting#setFalls}) holding when it gives way, what pushes it, where it stands and when
 * it sinks. No piece is cut, simulated or uploaded per frame, so a city falls in the draws its standing buildings took.
 * Render thread only; presentation, never game state: the rules decide what falls and the terrain installs the rubble.
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
    /** A falling part breaks into at most this many fragments (KitFragments), its batch's table in the shader. */
    static final int FRAGMENTS = 16;
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
     * The rubble the rules leave rises out of the ground over this long (#rubble), from as its last pieces touch down,
     * RISE_LEAD before its pile has settled (#settles): not before its pieces are down (user, 2026-11-09).
     */
    static final float RISE_SECONDS = .4f, RISE_LEAD = .3f;
    /**
     * A piece thrown past the board's edge shrinks to nothing this long after passing its floor's height: here, and the
     * cut pieces of MeepleDebris.
     */
    static final float EDGE_SECONDS = .6f;
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
     * The falls raise one cloud per {@link #CLOUD_HEXES} hexes square; falls of its square that start within
     * {@link #JOIN_SECONDS} of a cloud's first belong to it. At most {@link #MAX_CLOUDS} hang at once: under a strike
     * the squares that fall later raise none, rather than take a standing cloud away.
     */
    private static final int CLOUD_HEXES = 4, MAX_CLOUDS = 48;
    private static final float JOIN_SECONDS = 1;
    /** Pale, a little warm, concrete and plaster dust. */
    static final GpuExplosionEffects.Look DUST = new GpuExplosionEffects.Look(-1, 0, .15f);

    /** The fall's numbers as GLSL constants, so that the shader and {@link #settles} agree. */
    static String defines() {
        return "#define FALL_TEXELS " + TEXELS + "\n#define FALL_LOADED " + LOADED + "\n#define FALL_BLAST " + BLAST
              + "\n#define FALL_BURST " + BURST + "\n#define FALL_BURST_SPEED " + BURST_SPEED
              + "\n#define FALL_FRAGMENTS " + FRAGMENTS
              + "\n#define FALL_CRUSH " + CRUSH
              + "\n#define FALL_SPILL " + SPILL + "\n#define FALL_TUMBLE " + TUMBLE + "\n#define FALL_TOPPLE_OUT " + TOPPLE_OUT
              + "\n#define FALL_PUSH " + PUSH + "\n#define FALL_TOPPLE " + TOPPLE + "\n#define FALL_LOAD_DROP " + LOAD_DROP
              + "\n#define FALL_GROUND_DRAG " + GROUND_DRAG + "\n#define FALL_GROUND_SPIN_DRAG " + GROUND_SPIN_DRAG
              + "\n#define FALL_SINK " + SINK_SECONDS + "\n#define FALL_RELEASE_SPAN " + RELEASE_SPAN
              + "\n#define FALL_SECONDS " + FALL_SECONDS + "\n#define FALL_PILE_LEVELS " + PILE_LEVELS
              + "\n#define FALL_RISE " + RISE_SECONDS + "\n#define FALL_RISE_LEAD " + (REST_SECONDS + RISE_LEAD)
              + "\n#define FALL_EDGE " + EDGE_SECONDS + "\n";
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
        /** No structure falls here: its hex's rubble waits for the old path's pieces to land (#landed). */
        boolean rubbleOnly;
        /** A cloud holds its dust (#raise). */
        boolean dusty;
        /** Where the trees an attack broke stood (#breakTrees): their chunks' stands leave them out. */
        Set<Long> stood = Set.of();

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

    private final GpuLavaLighting lights;
    private final Map<Coords, Fall> falls = new LinkedHashMap<>();
    /** The hexes whose trees a blast fells, by hex: they leave their chunks' stands for {@link #fallen}. */
    private final Map<Coords, Fall> groves = new LinkedHashMap<>();
    /** The trees attacks broke, each break a slot of its own: a volley's next one at a hex never cuts the last short. */
    private final List<Fall> shattered = new ArrayList<>();
    /** Every felled tree, its instance naming its hex's slot + 1, and where each stood (chunks leave those out). */
    private final GpuTreeInstances.Stand fallen = new GpuTreeInstances.Stand();
    private final Set<Long> felled = new java.util.HashSet<>();
    private final GpuTreeInstances fallenBatches;
    /** Every fall's pieces and their slots + 1 (a_instance1.w), in one list, rebuilt when a fall comes or goes. */
    private final Array<Renderable> pieces = new Array<>();
    private final IntArray owners = new IntArray();
    private final GpuTreeInstances batches = new GpuTreeInstances((name, level) -> {
        throw new IllegalStateException("Debris draw no trees");
    }, false, true);
    /** The pieces grouped by batch, ready for every pass until a fall comes or goes; null without any. */
    private GpuTreeInstances.Placements placements;
    private float[] texels = new float[0];
    private boolean changed;
    /** Counts the changes of which hexes are taken: a chunk snapshot of another count may draw them wrongly. */
    private int revision;
    private float clock, gravity = GRAVITY;
    private final List<Cloud> clouds = new ArrayList<>();

    /**
     * The dust over the falls of one square of hexes, from the first of them on. It outlives their piles: a pile has
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

    /** {@code trees}: loads a tree model at a level by asset name, as the terrain's stands do. */
    GpuDestruction(GpuLavaLighting lights, BiFunction<String, Integer, Model> trees) {
        this.lights = lights;
        fallenBatches = new GpuTreeInstances(trees, true);
    }

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
     */
    void take(Coords at, Array<Renderable> pieces, Set<GpuBuilding.Assembly> buildings, BoundingBox bounds,
          float middleX, float middleY, Vector2 push, int flags) {
        Fall previous = falls.remove(at);
        if (previous == null) { revision++; }
        int slot = previous != null ? previous.slot : freeSlot();
        Fall fall = new Fall(at, slot, pieces, buildings, List.of(), Set.of(), new BoundingBox(bounds));
        site(fall, bounds, middleX, middleY, push, flags);
        falls.put(at, fall);
        write(fall);
        changed = true;
    }

    /**
     * Fells these trees of the hex, which stood for {@code features} of its scene tile: they leave their chunks' stands
     * and stand where they stood until {@link #startTrees}, then fall away along {@code push}. {@code bounds} holds them
     * standing, around the hex's middle.
     */
    void takeTrees(Coords at, List<Tree> trees, Set<BoardScene.Feature> features, BoundingBox bounds, float middleX,
          float middleY, Vector2 push) {
        Fall previous = groves.remove(at);
        int slot = previous != null ? previous.slot : freeSlot();
        Fall fall = new Fall(at, slot, new Array<>(), Set.of(), List.copyOf(trees), Set.copyOf(features),
              new BoundingBox(bounds));
        site(fall, bounds, middleX, middleY, push, 0);
        groves.put(at, fall);
        // Its chunk restands without them before the next publish (GpuTerrain#strike).
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
        Fall fall = new Fall(at, freeSlot(), pieces, Set.of(), List.of(), Set.copyOf(features), new BoundingBox(bounds));
        Set<Long> spots = new HashSet<>();
        for (Matrix4 transform : stood) { spots.add(spot(transform)); }
        fall.stood = spots;
        site(fall, bounds, middleX, middleY, push, BURST);
        shattered.add(fall);
        // Its chunk restands without them before the next publish (GpuTerrain#breakTrees).
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
     * out of sight under the ground until the pieces are down, then risen into place as they settle, before they sink
     * into it (tree-instances.glsl). A hex the old path breaks (GpuCollapse) has no fall here: its rubble waits for
     * {@link #landed}. It replaces the rubble given before.
     */
    void rubble(Coords at, Array<Renderable> pieces, BoundingBox bounds, float middleX, float middleY) {
        Fall fall = falls.get(at);
        if (fall == null) {
            // No chunk snapshot leaves anything out for it: no revision.
            fall = new Fall(at, freeSlot(), new Array<>(), Set.of(), List.of(), Set.of(), new BoundingBox(bounds));
            fall.rubbleOnly = true;
            site(fall, bounds, middleX, middleY, new Vector2(), 0);
            falls.put(at, fall);
        }
        for (int index = fall.pieces.size - 1; index >= 0; index--) {
            if (fall.pieces.get(index).userData == GpuInstancedMesh.RUBBLE) { fall.pieces.removeIndex(index); }
        }
        for (Renderable piece : pieces) { piece.userData = GpuInstancedMesh.RUBBLE; }
        fall.pieces.addAll(pieces);
        write(fall);
        changed = true;
    }

    /** The old path's pieces at the hex are down (GpuCollapse): its rubble rises now. */
    void landed(Coords at) {
        Fall fall = falls.get(at);
        if (fall == null || !fall.rubbleOnly || fall.start < NEVER) { return; }
        fall.start = clock;
        fall.gone = Math.min(fall.gone, clock);
        write(fall);
    }

    /** The hexes whose structures lie as pieces, or wait for their rubble ({@link #rubble}). */
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
        // Landed already (#landed): its rubble rises from now.
        if (fall.rubbleOnly) { return fall.start + RISE_LEAD; }
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
        if (falls.values().removeIf(fall -> clock >= fall.sinks + SINK_SECONDS)
              | groves.values().removeIf(fall -> clock >= fall.sinks + SINK_SECONDS)
              | shattered.removeIf(fall -> clock >= fall.sinks + SINK_SECONDS)) {
            changed = true;
            revision++;
        }
        for (Cloud cloud : clouds) { cloud.seconds += Math.max(0, seconds); }
        clouds.removeIf(cloud -> cloud.seconds >= CLOUD_SECONDS);
        raise();
        if (isEmpty()) { clock = 0; }
        publish();
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
        falls.clear();
        groves.clear();
        shattered.clear();
        clouds.clear();
        clock = 0;
        publish();
    }

    /**
     * A cloud for every square of hexes in which a structure has just given way, over all its structures giving way
     * with it: a building of many hexes, or a city's under a strike, raises few clouds, each drawn once.
     */
    private void raise() {
        Map<Long, List<Fall>> squares = new LinkedHashMap<>();
        for (Fall fall : falls.values()) {
            // The old path's collapses raise their own dust (GpuCollapse).
            if (!fall.dusty && !fall.rubbleOnly && clock >= fall.start) { squares.put(square(fall), new ArrayList<>()); }
        }
        if (squares.isEmpty()) { return; }
        for (Fall fall : falls.values()) {
            List<Fall> members = squares.get(square(fall));
            if (members != null && !fall.dusty && !fall.rubbleOnly && fall.start <= clock + JOIN_SECONDS) { members.add(fall); }
        }
        float hex = BoardGeometry.height() * .5f;
        for (List<Fall> members : squares.values()) {
            float x = 0, y = 0, floor = Float.MAX_VALUE, height = 0;
            for (Fall fall : members) {
                fall.dusty = true;
                x += fall.texels[4] / members.size();
                y += fall.texels[5] / members.size();
                floor = Math.min(floor, fall.bounds.min.z);
                height = Math.max(height, fall.bounds.getDepth());
            }
            if (clouds.size() < MAX_CLOUDS) {
                clouds.add(new Cloud(x, y, floor, height, hex * Math.min(CLOUD_HEXES, (float) Math.sqrt(members.size())),
                      members.getFirst().slot));
            }
        }
    }

    private static long square(Fall fall) {
        return (long) Math.floorDiv(fall.coords.getX(), CLOUD_HEXES) << 32
              | Math.floorDiv(fall.coords.getY(), CLOUD_HEXES) & 0xffffffffL;
    }

    /** The dust of the structures that gave way, this frame. */
    void dust(GpuExplosionEffects effects) {
        for (Cloud cloud : clouds) {
            cloud(effects, DUST, cloud.x, cloud.y, cloud.floor, cloud.height, cloud.reach, cloud.seconds, cloud.seed, true);
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

    /** The kit assemblies the falling pieces borrow: the terrain keeps them while any falls. */
    Set<GpuBuilding.Assembly> buildings() {
        Set<GpuBuilding.Assembly> result = new HashSet<>();
        for (Fall fall : falls.values()) { result.addAll(fall.buildings); }
        return result;
    }

    /** The meshes the falling pieces borrow: their instanced copies stay while any falls. */
    Set<Mesh> meshes() {
        Set<Mesh> result = new HashSet<>();
        for (Renderable piece : pieces) { result.add(piece.meshPart.mesh); }
        return result;
    }

    /**
     * The falling pieces and felled trees in this pass, instanced, as taken and started so far this frame: none when none
     * falls. The shadow map takes the trees at one coarse level; the fade pass only the trees' faded texels.
     */
    RenderableProvider pieces(GpuTreeInstances.Pass pass) {
        publish();
        batches.begin(pass);
        if (placements != null && pass != GpuTreeInstances.Pass.FADE) { batches.add(placements); }
        fallenBatches.begin(pass);
        if (!groves.isEmpty()) {
            if (pass == GpuTreeInstances.Pass.SHADOW) { fallenBatches.add(fallen, SHADOW_LEVEL, true); }
            else { fallenBatches.add(fallen, true); }
        }
        return (out, pool) -> {
            batches.getRenderables(out, pool);
            fallenBatches.getRenderables(out, pool);
        };
    }

    private int freeSlot() {
        var used = new java.util.BitSet();
        for (Fall fall : falls.values()) { used.set(fall.slot); }
        for (Fall fall : groves.values()) { used.set(fall.slot); }
        for (Fall fall : shattered) { used.set(fall.slot); }
        return used.nextClearBit(0);
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
        if (changed) {
            pieces.clear();
            owners.clear();
            for (Fall fall : pieceFalls()) {
                pieces.addAll(fall.pieces);
                for (int i = 0; i < fall.pieces.size; i++) { owners.add(fall.slot + 1); }
            }
            fallen.clear();
            felled.clear();
            for (Fall fall : shattered) { felled.addAll(fall.stood); }
            for (Fall fall : groves.values()) {
                for (Tree tree : fall.trees) {
                    fallen.add(tree.species(), tree.transform(), tree.diameter(), fall.slot + 1);
                    felled.add(spot(tree.transform()));
                }
            }
            lights.setFalls(isEmpty() ? new float[0] : texels);
            // The debris batches of the kits no fall uses any more.
            batches.retainParts(meshes());
            placements = pieces.isEmpty() ? null : batches.placements(pieces, owners);
            changed = false;
        }
        lights.setFallClock(clock, gravity, columns, rows, !isEmpty());
    }

    @Override
    public void dispose() {
        falls.clear();
        groves.clear();
        shattered.clear();
        pieces.clear();
        owners.clear();
        batches.dispose();
        fallenBatches.dispose();
    }
}
