/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.RenderableProvider;
import com.badlogic.gdx.math.MathUtils;
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
    /**
     * The collapse's physics, in playback seconds and world units: each storey gives way this long after the one below
     * it; the crushed foot bursts out this fast, the storeys above spill out and tumble up to these rates and topple
     * outward; a hit at full strength pushes and topples them away from it; a unit's weight drives them down.
     */
    static final float STOREY_RELEASE = .04f, CRUSH = 30, SPILL = 16, TUMBLE = 4, TOPPLE_OUT = 2.5f, PUSH = 40,
          TOPPLE = 4, LOAD_DROP = 40;
    /** Standard gravity on the table, and how fast a piece on the ground loses its speed and its spin, per second. */
    static final float GRAVITY = 450, GROUND_DRAG = 12, GROUND_SPIN_DRAG = 7;
    /** A pile rests this long once the terrain shows the rubble under it, then sinks into it over SINK_SECONDS. */
    static final float REST_SECONDS = 2.5f, SINK_SECONDS = 1.6f;
    /** A time that never comes: standing, or not sinking. */
    private static final float NEVER = 1e9f;
    /** The longest a piece falls before its pile rests, as at zero gravity, where it would drift on. */
    private static final float LONGEST_FALL = 6;
    /**
     * Dust, in playback seconds: a surge rolls out along the ground for this long, a column rises for longer. Falls by the
     * dozen (a strike) raise one cloud per {@link #CLOUD_HEXES} hexes square, at most {@link #MAX_CLOUDS} of them.
     */
    private static final float SURGE_SECONDS = 2.6f, COLUMN_SECONDS = 4.5f;
    private static final int ALONE = 8, CLOUD_HEXES = 4, MAX_CLOUDS = 24;
    private static final GpuExplosionEffects.Look DUST = new GpuExplosionEffects.Look(-1, 0, .15f);

    /** The fall's numbers as GLSL constants, so that the shader and {@link #settles} agree. */
    static String defines() {
        return "#define FALL_TEXELS " + TEXELS + "\n#define FALL_LOADED " + LOADED
              + "\n#define FALL_STOREY_RELEASE " + STOREY_RELEASE + "\n#define FALL_CRUSH " + CRUSH
              + "\n#define FALL_SPILL " + SPILL + "\n#define FALL_TUMBLE " + TUMBLE + "\n#define FALL_TOPPLE_OUT " + TOPPLE_OUT
              + "\n#define FALL_PUSH " + PUSH + "\n#define FALL_TOPPLE " + TOPPLE + "\n#define FALL_LOAD_DROP " + LOAD_DROP
              + "\n#define FALL_GROUND_DRAG " + GROUND_DRAG + "\n#define FALL_GROUND_SPIN_DRAG " + GROUND_SPIN_DRAG
              + "\n#define FALL_SINK " + SINK_SECONDS + "\n";
    }

    /**
     * One falling hex: its slot, its pieces (each drawn instanced, its node's box in its mesh part), the kit assemblies and
     * meshes they borrow, and its bounds standing.
     */
    private static final class Fall {
        final int slot;
        final Array<Renderable> pieces;
        final Set<GpuBuilding.Assembly> buildings;
        final BoundingBox bounds;
        final float[] texels = new float[TEXELS * 4];
        final Coords coords;
        /** When it gives way, when the terrain first showed the rubble under it, and when its pile sinks. */
        float start = NEVER, gone = NEVER, sinks = NEVER;

        Fall(Coords coords, int slot, Array<Renderable> pieces, Set<GpuBuilding.Assembly> buildings, BoundingBox bounds) {
            this.coords = coords;
            this.slot = slot;
            this.pieces = pieces;
            this.buildings = buildings;
            this.bounds = bounds;
        }
    }

    private final GpuLavaLighting lights;
    private final Map<Coords, Fall> falls = new LinkedHashMap<>();
    /** Every fall's pieces and their slots + 1 (a_instance1.w), in one list, rebuilt when a fall comes or goes. */
    private final Array<Renderable> pieces = new Array<>();
    private final IntArray owners = new IntArray();
    private final GpuTreeInstances batches = new GpuTreeInstances((name, level) -> {
        throw new IllegalStateException("Debris draw no trees");
    });
    /** The pieces grouped by batch, ready for every pass until a fall comes or goes; null without any. */
    private GpuTreeInstances.Placements placements;
    private float[] texels = new float[0];
    private boolean changed;
    /** Counts the changes of which hexes are taken: a chunk snapshot of another count may draw them wrongly. */
    private int revision;
    private float clock, gravity = GRAVITY;

    GpuDestruction(GpuLavaLighting lights) { this.lights = lights; }

    /** The falls' clock: playback seconds, which stop while it is paused and follow its speed. */
    float clock() { return clock; }

    /** Whether the hex's structure has left its chunk for its pieces here: its chunk must not draw it. */
    boolean taken(Coords at) { return falls.containsKey(at); }

    /** Which hexes are taken, as a count of their changes: a chunk snapshot taken at another count is stale. */
    int revision() { return revision; }

    /** Whether the hex's structure has begun to give way: nothing may pick it any more. */
    boolean started(Coords at) {
        Fall fall = falls.get(at);
        return fall != null && clock >= fall.start;
    }

    boolean isEmpty() { return falls.isEmpty(); }

    /**
     * Takes the hex's structure from its chunk: {@code pieces} stand where it stood until {@link #start}. {@code floor}
     * and {@code top} bound it, around its hex's middle ({@code middleX}, {@code middleY}); {@code push} is the hit's
     * direction times its strength (0 to 1, at most 3 for a blast), zero for its own weight.
     */
    void take(Coords at, Array<Renderable> pieces, Set<GpuBuilding.Assembly> buildings, BoundingBox bounds,
          float middleX, float middleY, Vector2 push, int flags) {
        Fall previous = falls.remove(at);
        if (previous == null) { revision++; }
        int slot = previous != null ? previous.slot : freeSlot();
        Fall fall = new Fall(at, slot, pieces, buildings, new BoundingBox(bounds));
        fall.texels[1] = flags;
        fall.texels[2] = push.x;
        fall.texels[3] = push.y;
        fall.texels[4] = middleX;
        fall.texels[5] = middleY;
        fall.texels[6] = bounds.min.z;
        fall.texels[7] = bounds.max.z;
        falls.put(at, fall);
        write(fall);
        changed = true;
    }

    /** The hex's structure gives way {@code delay} playback seconds from now, unless it already has. */
    void start(Coords at, float delay) {
        Fall fall = falls.get(at);
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
    void committed(Predicate<Coords> standing) {
        falls.forEach((at, fall) -> {
            if (fall.gone < NEVER || standing.test(at)) { return; }
            fall.gone = clock;
            write(fall);
        });
    }

    /**
     * When the hex's pieces have all come to rest: its top storey gives way last, falls its height and skids on (the
     * shader's own bound, from the same constants).
     */
    private float settles(Fall fall) {
        float level = Math.max(1e-3f, BoardGeometry.level()), height = Math.max(fall.bounds.getDepth(), level);
        float drop = gravity > 0 ? (float) Math.sqrt(2 * height / gravity) : LONGEST_FALL;
        return fall.start + STOREY_RELEASE * height / level * 1.2f + Math.min(LONGEST_FALL, drop) + .5f;
    }

    /**
     * Runs the falls' clock on by {@code seconds} of playback under {@code gravity} standard gravities and retires the
     * piles that have sunk.
     */
    void advance(float seconds, float gravity) {
        clock += Math.max(0, seconds);
        this.gravity = GRAVITY * gravity;
        if (falls.values().removeIf(fall -> clock >= fall.sinks + SINK_SECONDS)) {
            changed = true;
            revision++;
        }
        if (falls.isEmpty()) { clock = 0; }
        publish();
    }

    /** The hexes whose structures lie as pieces instead of in their chunks. */
    Set<Coords> taken() { return Set.copyOf(falls.keySet()); }

    /** Every fall ends at once (Instant, a skipped playback): its pieces go with the terrain's next scene. */
    void clear() {
        if (!falls.isEmpty()) {
            changed = true;
            revision++;
        }
        falls.clear();
        clock = 0;
        publish();
    }

    /**
     * The dust of the structures giving way, as their pieces start to move: a surge rolling out along the ground from
     * their feet and a column rising from their wreck. Falls by the dozen share clouds a few hexes across, each from its
     * first fall over all of them, so that a city's dust stays within the effects' volumes.
     */
    void dust(GpuExplosionEffects effects) {
        List<Fall> started = new ArrayList<>();
        for (Fall fall : falls.values()) { if (clock >= fall.start) { started.add(fall); } }
        if (started.isEmpty()) { return; }
        float hex = BoardGeometry.height() * .5f;
        if (started.size() <= ALONE) {
            for (Fall fall : started) {
                cloud(effects, fall.texels[4], fall.texels[5], fall.bounds.min.z, fall.bounds.getDepth(), hex,
                      clock - fall.start, fall.slot, started.size());
            }
            return;
        }
        Map<Long, List<Fall>> cells = new LinkedHashMap<>();
        for (Fall fall : started) {
            long key = (long) Math.floorDiv(fall.coords.getX(), CLOUD_HEXES) << 32
                  | Math.floorDiv(fall.coords.getY(), CLOUD_HEXES) & 0xffffffffL;
            cells.computeIfAbsent(key, ignored -> new ArrayList<>()).add(fall);
        }
        List<List<Fall>> clouds = new ArrayList<>(cells.values());
        clouds.sort(Comparator.comparingDouble(cell -> cell.stream().mapToDouble(fall -> fall.start).min().orElse(0)));
        int count = Math.min(MAX_CLOUDS, clouds.size());
        for (List<Fall> cell : clouds.subList(0, count)) {
            float x = 0, y = 0, floor = Float.MAX_VALUE, height = 0, first = NEVER;
            for (Fall fall : cell) {
                x += fall.texels[4] / cell.size();
                y += fall.texels[5] / cell.size();
                floor = Math.min(floor, fall.bounds.min.z);
                height = Math.max(height, fall.bounds.getDepth());
                first = Math.min(first, fall.start);
            }
            float reach = hex * Math.min(CLOUD_HEXES, 1 + (float) Math.sqrt(cell.size()));
            cloud(effects, x, y, floor, height, reach, clock - first, cell.getFirst().slot, count);
        }
    }

    /** One cloud: its surge from around ({@code x}, {@code y}) at {@code floor}, its column up to about {@code height}. */
    private static void cloud(GpuExplosionEffects effects, float x, float y, float floor, float height, float reach,
          float seconds, int seed, int clouds) {
        float share = Math.min(1, 4f / Math.max(clouds, 1));
        var point = new Vector3();
        var axis = new Vector3();
        float surge = seconds / SURGE_SECONDS;
        if (surge < 1) {
            float spread = 1 - (float) Math.exp(-surge * 3.5f);
            int puffs = Math.max(5, Math.round(10 * share));
            for (int puff = 0; puff < puffs; puff++) {
                float bearing = puff * MathUtils.PI2 / puffs + GpuArtilleryBlast.random(seed, puff) * .4f;
                float out = reach * (.7f + 1.1f * spread * MathUtils.lerp(.6f, 1.2f, GpuArtilleryBlast.random(seed, puff + 20)));
                float radius = reach * .35f * (.6f + .9f * surge) * MathUtils.lerp(.7f, 1.3f, GpuArtilleryBlast.random(seed, puff + 40));
                point.set(x + MathUtils.cos(bearing) * out, y + MathUtils.sin(bearing) * out, floor + reach * .1f);
                // Drawn out around the ring, so neighbouring puffs merge into one sheet of dust rolling outward.
                axis.set(-MathUtils.sin(bearing), MathUtils.cos(bearing), 0).scl(1.1f);
                effects.add(point, radius, surge, 0, .45f * GpuArtilleryBlast.fadeOut(surge, .35f), seed + puff * 31, DUST, axis);
            }
        }
        float column = seconds / COLUMN_SECONDS;
        if (column < 1) {
            float alpha = MathUtils.clamp(seconds / .5f, 0, 1) * GpuArtilleryBlast.fadeOut(column, .45f);
            for (int puff = 0, puffs = Math.max(2, Math.round(4 * share)); puff < puffs; puff++) {
                float bearing = puff * 2.1f + GpuArtilleryBlast.random(seed, puff + 60);
                point.set(x + MathUtils.cos(bearing) * reach * .3f, y + MathUtils.sin(bearing) * reach * .3f,
                      floor + height * (.2f + puff * .22f) + reach * column * .8f);
                effects.add(point, reach * (.45f + .5f * column) * (1 - puff * .08f), column, 0, .35f * alpha,
                      seed + 97 + puff * 37, DUST, Vector3.Zero);
            }
        }
    }

    /** The falling structures' bounds, standing; empty without any. */
    BoundingBox bounds(BoundingBox out) {
        out.inf();
        for (Fall fall : falls.values()) { out.ext(fall.bounds); }
        return out;
    }

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

    /** The falling pieces in this pass, instanced, as taken and started so far this frame: none when none falls. */
    RenderableProvider pieces(GpuTreeInstances.Pass pass) {
        publish();
        batches.begin(pass);
        if (placements != null) { batches.add(placements); }
        return batches;
    }

    private int freeSlot() {
        var used = new java.util.BitSet();
        for (Fall fall : falls.values()) { used.set(fall.slot); }
        return used.nextClearBit(0);
    }

    private void write(Fall fall) {
        // A pile sinks once it has settled and the terrain shows the rubble under it, whichever comes later.
        fall.sinks = fall.start < NEVER && fall.gone < NEVER ? Math.max(fall.gone, settles(fall)) + REST_SECONDS : NEVER;
        fall.texels[0] = fall.start;
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
            for (Fall fall : falls.values()) {
                pieces.addAll(fall.pieces);
                for (int i = 0; i < fall.pieces.size; i++) { owners.add(fall.slot + 1); }
            }
            lights.setFalls(falls.isEmpty() ? new float[0] : texels);
            // The debris batches of the kits no fall uses any more.
            batches.retainParts(meshes());
            placements = pieces.isEmpty() ? null : batches.placements(pieces, owners);
            changed = false;
        }
        lights.setFallClock(clock, gravity, !falls.isEmpty());
    }

    @Override
    public void dispose() {
        falls.clear();
        pieces.clear();
        owners.clear();
        batches.dispose();
    }
}
