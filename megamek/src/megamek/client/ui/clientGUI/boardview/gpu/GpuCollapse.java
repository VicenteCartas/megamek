/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import megamek.logging.MMLogger;

/**
 * One hex's structure coming down: a building, a fuel tank or a bridge span, read where the terrain drew it, cut into
 * closed pieces ({@link MeepleShatter}) on another thread, then dropped where they stood ({@link MeepleDebris#collapse})
 * in a cloud of dust, until they have settled and sink into the rubble the hex shows now. Owns its pieces' meshes and
 * borrows their materials. The render thread drives it, the cut aside.
 */
final class GpuCollapse implements Disposable {
    private static final MMLogger LOGGER = MMLogger.create(GpuCollapse.class);
    /** Playback seconds its pieces lie at rest before they sink, and how long they take to sink. */
    static final float REST_SECONDS = 2.5f, SINK_SECONDS = 1.6f;
    /** Playback seconds after which pieces still rocking count as settled, as at zero gravity. */
    private static final float LONGEST_FALL = 6;
    /** How long the dust of the foot giving way, its column and a piece's landing last, in playback seconds. */
    private static final float SURGE_SECONDS = 2.6f, COLUMN_SECONDS = 4.5f, PUFF_SECONDS = 1.4f;
    /** At most this many landings raise their own dust: the explosion volumes are shared with every other effect. */
    private static final int MAX_PUFFS = 16;
    /** Pale, a little warm, concrete and plaster dust; over water, white spray. */
    private static final GpuExplosionEffects.Look DUST = new GpuExplosionEffects.Look(-1, 0, .15f);
    private static final GpuExplosionEffects.Look SPRAY = new GpuExplosionEffects.Look(-1, 0, 0);

    /** Cuts run here, one at a time: off the render thread, and off the common pool, whose work the render thread joins. */
    private static final ExecutorService CUTTER = Executors.newSingleThreadExecutor(work -> {
        var thread = new Thread(work, "Collapse cutter");
        thread.setDaemon(true);
        return thread;
    });

    private record Puff(Vector3 at, float size, float born, int seed) { }

    private final CompletableFuture<MeepleShatter.Cut> cut;
    private final MeepleDebris.Collapse fall;
    /** The middle of the structure's foot, its horizontal reach from there and its height. */
    private final Vector3 base;
    private final float reach, height;
    /** Its pieces fall into water: their landings throw spray, not dust. */
    private final boolean water;
    private final int seed;
    private final List<Puff> puffs = new ArrayList<>();
    private Model model;
    private ModelInstance instance;
    private MeepleDebris debris;
    private boolean failed, released;
    private float seconds, settled = Float.NaN;

    /**
     * Starts cutting {@code sources}, read on this thread, on another.
     *
     * @param origin where the sources' frame lies on the board, near them
     * @param fall   how the pieces come down
     * @param reach  the structure's horizontal reach from its foot's middle ({@code fall.base()})
     * @param height its height above its foot
     * @param water  whether its pieces fall into water
     */
    GpuCollapse(List<MeepleShatter.Source> sources, Vector3 origin, MeepleShatter.Planes planes, MeepleShatter.Caps caps,
          MeepleDebris.Collapse fall, float reach, float height, boolean water, int seed) {
        cut = CompletableFuture.supplyAsync(MeepleShatter.cutter(sources, origin, Vector3.Z, planes, seed, caps), CUTTER);
        this.fall = fall;
        base = new Vector3(fall.base());
        this.reach = Math.max(reach, 1);
        this.height = Math.max(height, 1);
        this.water = water;
        this.seed = seed;
    }

    /** Whether its pieces are cut, or the cut failed: it can take the structure's place. */
    boolean ready() { return cut.isDone(); }

    /** Whether its pieces fall, from the frame they took the structure's place. */
    boolean started() { return debris != null; }

    /**
     * Makes its pieces where the structure stood and lets them fall. False when they could not be cut: then it shows
     * nothing, and the structure simply makes way for what replaces it.
     */
    boolean start() {
        if (debris != null) { return true; }
        if (failed) { return false; }
        try {
            var pieces = GpuAssets.joined(cut);
            if (pieces.pieces() == 0) {
                failed = true;
                return false;
            }
            model = MeepleShatter.model("collapse", pieces);
            instance = new ModelInstance(model);
            debris = MeepleDebris.collapse(instance, instance.nodes.first(), pieces.supports(), fall, seed);
            debris.pose();
            return true;
        } catch (RuntimeException failure) {
            LOGGER.warn("A collapsing structure could not be broken into pieces; it makes way without them", failure);
            failed = true;
            return false;
        }
    }

    /** Its pieces, or null before they take the structure's place. */
    ModelInstance instance() { return debris == null ? null : instance; }

    /** Lets its pieces fall: until then they stand where the structure stood. */
    void release() { released = true; }

    /**
     * Runs its pieces on by {@code seconds} of playback time, under {@code gravity} standard gravities, landing on
     * {@code ground}; once they have lain at rest a while they sink away.
     */
    void advance(float seconds, MeepleDebris.Ground ground, float gravity) {
        if (debris == null || !released) { return; }
        this.seconds += seconds;
        debris.advance(seconds, ground, gravity);
        for (var landing : debris.landings()) {
            if (puffs.size() < MAX_PUFFS) {
                puffs.add(new Puff(landing.point(), Math.min(landing.size(), reach * .5f), this.seconds, seed + 7 * puffs.size()));
            }
        }
        if (Float.isNaN(settled) && (debris.settled() || this.seconds > LONGEST_FALL)) { settled = this.seconds; }
        debris.sink(UnitAttack.smooth(sinking()));
        debris.pose();
    }

    /** How far its pieces have sunk away, 0 to 1. */
    private float sinking() {
        return Float.isNaN(settled) ? 0 : MathUtils.clamp((seconds - settled - REST_SECONDS) / SINK_SECONDS, 0, 1);
    }

    /** Whether it has nothing more to show: its pieces have sunk away and its dust has cleared, or it never fell. */
    boolean done() {
        return failed || debris != null && sinking() >= 1 && seconds >= COLUMN_SECONDS;
    }

    /**
     * Its dust this frame: a surge racing out along the ground as a building's foot gives way (a span has none), a
     * column rising from the wreck, and a puff, or spray over water, where a piece lands hard. It shares the volumes the effects hold with the
     * {@code collapses} under way, itself among them, rather than crowding out the rest.
     */
    void explosions(GpuExplosionEffects effects, int collapses) {
        if (debris == null || !released) { return; }
        float share = Math.min(1, 4f / Math.max(collapses, 1));
        var look = water ? SPRAY : DUST;
        var point = new Vector3();
        var axis = new Vector3();
        float surge = seconds / SURGE_SECONDS;
        if (surge < 1 && !fall.span()) {
            float spread = 1 - (float) Math.exp(-surge * 3.5f);
            int puffs = Math.max(5, Math.round(10 * share));
            for (int puff = 0; puff < puffs; puff++) {
                float bearing = puff * MathUtils.PI2 / puffs + GpuArtilleryBlast.random(seed, puff) * .4f;
                float out = reach * (.7f + 1.1f * spread * MathUtils.lerp(.6f, 1.2f, GpuArtilleryBlast.random(seed, puff + 20)));
                float radius = reach * .35f * (.6f + .9f * surge) * MathUtils.lerp(.7f, 1.3f, GpuArtilleryBlast.random(seed, puff + 40));
                point.set(base).add(MathUtils.cos(bearing) * out, MathUtils.sin(bearing) * out, reach * .1f);
                // Drawn out around the ring, so neighbouring puffs merge into one sheet of dust rolling outward.
                axis.set(-MathUtils.sin(bearing), MathUtils.cos(bearing), 0).scl(1.1f);
                effects.add(point, radius, surge, 0, .45f * GpuArtilleryBlast.fadeOut(surge, .35f), seed + puff * 31, look, axis);
            }
        }
        float column = seconds / COLUMN_SECONDS;
        if (column < 1) {
            float alpha = MathUtils.clamp(seconds / .5f, 0, 1) * GpuArtilleryBlast.fadeOut(column, .45f);
            for (int puff = 0, puffs = Math.max(2, Math.round(4 * share)); puff < puffs; puff++) {
                float bearing = puff * 2.1f + GpuArtilleryBlast.random(seed, puff + 60);
                point.set(base).add(MathUtils.cos(bearing) * reach * .3f, MathUtils.sin(bearing) * reach * .3f,
                      height * (.2f + puff * .22f) + reach * column * .8f);
                effects.add(point, reach * (.45f + .5f * column) * (1 - puff * .08f), column, 0, .35f * alpha,
                      seed + 97 + puff * 37, look, Vector3.Zero);
            }
        }
        int landings = Math.max(2, Math.round(MAX_PUFFS * share));
        for (Puff puff : puffs.subList(0, Math.min(landings, puffs.size()))) {
            float age = (seconds - puff.born()) / PUFF_SECONDS;
            if (age >= 1) { continue; }
            point.set(puff.at()).add(0, 0, puff.size() * (.2f + .5f * age));
            effects.add(point, puff.size() * (.7f + .8f * age), age, 0, .5f * GpuArtilleryBlast.fadeOut(age, .3f), puff.seed(),
                  look, Vector3.Zero);
        }
    }

    @Override
    public void dispose() {
        if (model != null) { model.dispose(); }
        model = null;
        instance = null;
        debris = null;
        // A cut still running is dropped with it.
        cut.cancel(false);
    }
}
