/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.model.NodePart;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import megamek.logging.MMLogger;

/**
 * One hex's structure coming down: a building, a fuel tank or a bridge span, read where the terrain drew it, cut into
 * closed pieces ({@link MeepleShatter}) on another thread, then dropped where they stood ({@link MeepleDebris#collapse})
 * in a cloud of dust, until they have settled and sink into the rubble the hex shows now. Owns its pieces' meshes and
 * the masks its road coats sample, and borrows their other materials. The render thread drives it, the cut aside.
 */
final class GpuCollapse implements Disposable {
    private static final MMLogger LOGGER = MMLogger.create(GpuCollapse.class);
    /**
     * Playback seconds its pieces lie at rest before they sink, and how long they take to sink: as the kit structures'
     * pieces (GpuDestruction), as fast as a mek's death (user, 2026-11-09).
     */
    static final float REST_SECONDS = GpuDestruction.REST_SECONDS, SINK_SECONDS = GpuDestruction.SINK_SECONDS;
    /** Playback seconds after which pieces still rocking count as settled, as at zero gravity. */
    private static final float LONGEST_FALL = 6;
    /** How long the dust of a piece's landing lasts, in playback seconds. */
    private static final float PUFF_SECONDS = 1.4f;
    /** At most this many landings raise their own dust: the explosion volumes are shared with every other effect. */
    private static final int MAX_PUFFS = 16;
    /** Over water, white spray for dust. */
    private static final GpuExplosionEffects.Look SPRAY = new GpuExplosionEffects.Look(-1, 0, 0);

    /** Cuts run here, one at a time: off the render thread, and off the common pool, whose work the render thread joins. */
    private static final ExecutorService CUTTER = Executors.newSingleThreadExecutor(work -> {
        var thread = new Thread(work, "Collapse cutter");
        thread.setDaemon(true);
        return thread;
    });

    private record Puff(Vector3 at, float size, float born, int seed) { }

    /** A part of a piece that blends over the rest, with the renderable it is drawn through. */
    private record Overlay(Node piece, NodePart part, Renderable renderable) { }

    private final CompletableFuture<MeepleShatter.Cut> cut;
    /** The masks its road coats sample, null for none. */
    private final Disposable masks;
    private final List<Overlay> overlays = new ArrayList<>();
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
     * @param masks  the masks its road coats sample, which it disposes with itself; null for none
     */
    GpuCollapse(List<MeepleShatter.Source> sources, Vector3 origin, MeepleShatter.Planes planes, MeepleShatter.Caps caps,
          MeepleDebris.Collapse fall, float reach, float height, boolean water, int seed, Disposable masks) {
        this.masks = masks;
        cut = CompletableFuture.supplyAsync(MeepleShatter.cutter(sources, origin, Vector3.Z, planes, seed, caps), CUTTER);
        this.fall = fall;
        base = new Vector3(fall.base());
        this.reach = Math.max(reach, 1);
        this.height = Math.max(height, 1);
        this.water = water;
        this.seed = seed;
    }

    /** The middle of the structures {@code transition} brings down, at their ground: where the camera frames them. */
    static Vector3 middle(BoardScene.TileTransition transition) {
        var center = new Vector3();
        for (var collapse : transition.collapses()) {
            center.add(BoardGeometry.center(collapse.coords(), collapse.before().elevation()));
        }
        return center.scl(1f / transition.collapses().size());
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
            // What blends over the pieces, a road's coats on a span's deck, writes no depth and casts no shadow: the
            // solid passes leave it out, and it draws after them (renderOverlays), as the deck's coats did.
            for (Node piece : instance.nodes.first().getChildren()) {
                for (NodePart part : piece.parts) {
                    var blend = part.material.get(BlendingAttribute.class, BlendingAttribute.Type);
                    if (blend != null && blend.blended) {
                        part.enabled = false;
                        overlays.add(new Overlay(piece, part, new Renderable()));
                    }
                }
            }
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

    /** Draws what blends over its pieces (start), in the pass that draws the terrain's coats after its solids. */
    void renderOverlays(ModelBatch batch, Environment lighting) {
        if (instance() == null) { return; }
        for (Overlay overlay : overlays) {
            Renderable renderable = instance.getRenderable(overlay.renderable(), overlay.piece(), overlay.part());
            renderable.environment = lighting;
            renderable.shader = null;
            batch.render(renderable);
        }
    }

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

    /** Whether its pieces have come down and settled, or never fell: the rubble under them may show (GpuDestruction#landed). */
    boolean settled() { return failed || !Float.isNaN(settled); }

    /** How far its pieces have sunk away, 0 to 1. */
    private float sinking() {
        return Float.isNaN(settled) ? 0 : MathUtils.clamp((seconds - settled - REST_SECONDS) / SINK_SECONDS, 0, 1);
    }

    /** Whether it has nothing more to show: its pieces have sunk away and its dust has cleared, or it never fell. */
    boolean done() {
        return failed || debris != null && sinking() >= 1 && seconds >= GpuDestruction.CLOUD_SECONDS;
    }

    /**
     * Its dust this frame: its cloud (GpuDestruction#cloud; a span's has no surge) and a puff, or spray over water,
     * where a piece lands hard. It shares the volumes the effects hold with the {@code collapses} under way, itself
     * among them, rather than crowding out the rest.
     */
    void explosions(GpuExplosionEffects effects, int collapses) {
        if (debris == null || !released) { return; }
        float share = Math.min(1, 4f / Math.max(collapses, 1));
        var look = water ? SPRAY : GpuDestruction.DUST;
        var point = new Vector3();
        GpuDestruction.cloud(effects, look, base.x, base.y, base.z, height, reach, seconds, seed, !fall.span());
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
        overlays.clear();
        if (masks != null) { masks.dispose(); }
        // A cut still running is dropped with it.
        cut.cancel(false);
    }
}
