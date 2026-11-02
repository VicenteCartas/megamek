/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.Disposable;

/**
 * Meeples dying where they stood, after the view stopped drawing them for their unit: a destroyed unit's meeple and a
 * formation's lost vehicle explode into pieces ({@link MeepleShatter}, {@link MeepleDebris}); a formation's lost
 * soldier turns to the hit, is knocked back and falls on its back. A fallen soldier lies a moment, and the pieces fly
 * and settle, then they fade away: a wreck has no gameplay. View-owned and presentation only.
 */
final class MeepleCasualties implements Disposable {
    static final float FALL_SECONDS = .9f;
    static final float LIE_SECONDS = 1.5f;
    /** Thrown pieces start to fade ({@link UnitAttack#FADE_SECONDS}) this long after their explosion, at rest or flying. */
    static final float DEBRIS_SECONDS = 3;
    /** How far a soldier is knocked back, and how high it is thrown, in its standing heights. */
    private static final float KNOCKBACK = .6f, THROW = .18f;
    /** A destroyed unit's meeple breaks along this many planes, into up to sixteen pieces; a lost vehicle into four. */
    private static final int TOKEN_CUTS = 4, VEHICLE_CUTS = 2;

    /**
     * The instance the view drew for the unit until it died, holding only what is dying, and the unit as it was then
     * shown, whose camouflage it still wears.
     */
    static final class Casualty implements Disposable {
        final GpuUnitInstance instance;
        final BoardScene.Unit unit;
        /** The whole unit's death marker, or null for individual losses in a surviving formation. */
        final UnitFootprint.Pose pose;
        private final List<Soldier> soldiers = new ArrayList<>();
        private final List<Debris> debris = new ArrayList<>();
        private float seconds;

        private Casualty(GpuUnitInstance instance, BoardScene.Unit unit, UnitFootprint.Pose pose) {
            this.instance = instance;
            this.unit = unit;
            this.pose = pose;
            // It fades as its unit would have (GpuUnitShader.Shown).
            if (!(instance.userData instanceof GpuUnitShader.Shown)) { instance.userData = new GpuUnitShader.Shown(); }
        }

        float opacity() { return ((GpuUnitShader.Shown) instance.userData).opacity; }

        @Override
        public void dispose() { debris.forEach(pieces -> pieces.model().dispose()); }
    }

    /**
     * A fallen soldier: its node's local transform when it fell, its visible surface vertices and bounds' centre, the
     * ground under it, its height, the way it is knocked and the turn that makes it face the hit.
     */
    private record Soldier(Node node, Matrix4 rest, Vector3[] points, Vector3 center, float floor, float height,
          Vector3 away, float turn) { }

    /** Thrown pieces and the model that owns their meshes. */
    private record Debris(Model model, MeepleDebris pieces) { }

    private final List<Casualty> casualties = new ArrayList<>();
    /** Meeples cut ahead of their deaths ({@link #prepare}), by death: dropped with the playback's. */
    private final Map<UnitAttack, CompletableFuture<MeepleShatter.Cut>> prepared = new WeakHashMap<>();

    List<Casualty> casualties() { return casualties; }

    /** A repair or a replay returning before death replaces that unit's old casualty. */
    void revived(BoardScene.Unit unit) {
        casualties.removeIf(casualty -> {
            boolean replaced = casualty.pose != null && casualty.unit.id() == unit.id() && casualty.unit.part() == unit.part();
            if (replaced) { casualty.dispose(); }
            return replaced;
        });
    }

    /**
     * Cuts the meeple of a unit whose {@code death} is coming, once, on another thread: its explosion then only makes
     * the meshes, rather than stall the frame of the killing blow.
     *
     * @param standing the meeple as the view draws it
     */
    void prepare(UnitAttack death, GpuUnitInstance standing, BoardScene.Unit unit) {
        Node meeple = standing.getNode(MeepleVisual.ROOT);
        if (meeple == null || prepared.containsKey(death)) { return; }
        prepared.put(death, GpuAssets.pooled(cutter(standing, meeple, TOKEN_CUTS, unit.id())));
    }

    /**
     * Takes over a destroyed unit's last drawn pose. Crew loss fades that pose unchanged; otherwise a token shatters,
     * a formation's figures fall, and other models fade where the death's explosion plays.
     *
     * @param standing the meeple as the view last drew it, which it no longer draws
     * @param hit      horizontal, away from the hits, as long as the damage they dealt; null to burst out evenly
     * @param death    the death playing, whose cut {@link #prepare} may have made; null for a death that did not play
     */
    void died(GpuUnitInstance standing, BoardScene.Unit unit, Vector3 hit, UnitAttack death, UnitFootprint.Pose pose) {
        var casualty = new Casualty(standing, unit, pose);
        Node meeple = standing.getNode(MeepleVisual.ROOT);
        if (death == null || !death.event.result().crewLoss()) {
            if (meeple != null) {
                standing.nodes.clear();
                shatter(casualty, meeple, TOKEN_CUTS, unit.id(), hit, death == null ? null : prepared.remove(death));
            } else if (standing.visual() != null && standing.visual().infantry()) {
                List<Node> figures = new ArrayList<>();
                for (Node node : standing.nodes) {
                    if (standing.visual().rigs().stream().anyMatch(rig -> node.id.equals(rig.container()))) { figures.add(node); }
                }
                fall(casualty, figures, hit);
            }
        }
        casualties.add(casualty);
    }

    /**
     * Takes over the figures of {@code previous} that its formation's {@code next} assembly no longer has. Nothing if
     * it lost none.
     *
     * @param previous the instance the view drew for the unit until now, which it replaces
     * @param hit      horizontal, away from the hits, as long as the damage they dealt; null when unknown: soldiers
     *                 then fall out of their formation, clear of the survivors, and vehicles burst out evenly
     */
    void lost(GpuUnitInstance previous, GpuUnitModel next, BoardScene.Unit unit, Vector3 hit) {
        var was = previous.visual();
        Set<String> kept = new HashSet<>();
        next.instance.nodes.forEach(node -> kept.add(node.id));
        List<Node> lost = new ArrayList<>();
        for (Node node : previous.nodes) {
            if (!kept.contains(node.id) && was.rigs().stream().anyMatch(rig -> node.id.equals(rig.container()))) { lost.add(node); }
        }
        if (lost.isEmpty()) { return; }
        var casualty = new Casualty(previous, unit, null);
        fall(casualty, lost, hit);
        casualties.add(casualty);
    }

    private static void fall(Casualty casualty, List<Node> lost, Vector3 hit) {
        var previous = casualty.instance;
        var was = previous.visual();
        var middle = previous.transform.getTranslation(new Vector3());
        // Read each shared GLB mesh once for this group; only the posed contact points survive with its casualties.
        var surfaces = new UnitPicking();
        previous.nodes.clear();
        for (Node node : lost) {
            var rig = was.rigs().stream().filter(candidate -> node.id.equals(candidate.container())).findFirst().orElseThrow();
            var outward = UnitBounds.subtree(node).mul(previous.transform).getCenter(new Vector3()).sub(middle);
            outward.z = 0;
            var away = hit != null ? new Vector3(hit).nor() : outward.len2() < 1e-4f ? null : outward.nor();
            Node drawn = rig.transport() ? drawn(node) : null;
            if (drawn == null) {
                var points = surfaces.vertices(previous, node);
                if (!points.isEmpty()) {
                    previous.nodes.add(node);
                    casualty.soldiers.add(soldier(previous, node, away, points.toArray(Vector3[]::new)));
                }
            } else {
                shatter(casualty, drawn, VEHICLE_CUTS, casualty.unit.id() * 31 + node.id.hashCode(), hit, null);
            }
        }
        previous.calculateTransforms();
    }

    /**
     * Breaks {@code drawn}, a node of the casualty's instance, into pieces in its place and throws them: those of its
     * {@code prepared} cut if it is ready, else cut now.
     */
    private static void shatter(Casualty casualty, Node drawn, int cuts, int seed, Vector3 hit,
          CompletableFuture<MeepleShatter.Cut> prepared) {
        var ready = prepared == null ? null : prepared.getNow(null);
        Model model = ready == null ? null : MeepleShatter.model(drawn.id, drawn, ready);
        if (model == null) {
            model = MeepleShatter.model(drawn.id, drawn, cutter(casualty.instance, drawn, cuts, seed).get());
        }
        Node pieces = model.nodes.first().copy();
        pieces.isAnimated = true;
        pieces.localTransform.set(drawn.globalTransform);
        casualty.instance.nodes.add(pieces);
        casualty.debris.add(new Debris(model, new MeepleDebris(casualty.instance, pieces, hit, seed)));
    }

    /** Cuts in the mesh's own coordinates, where its camouflage was projected, about the board's up there. */
    private static Supplier<MeepleShatter.Cut> cutter(ModelInstance instance, Node drawn, int cuts, int seed) {
        var frame = new Matrix4(instance.transform).mul(drawn.globalTransform);
        var up = new Vector3(Vector3.Z).rot(new Matrix4(frame).inv());
        return MeepleShatter.cutter(drawn, up, cuts, seed);
    }

    /** The first node of the subtree that draws anything: a meeple vehicle's single mesh. */
    private static Node drawn(Node node) {
        for (var part : node.parts) {
            if (part.enabled) { return node; }
        }
        for (Node child : node.getChildren()) {
            Node found = drawn(child);
            if (found != null) { return found; }
        }
        return null;
    }

    private static Soldier soldier(ModelInstance instance, Node node, Vector3 away, Vector3[] points) {
        var rest = new Matrix4(node.calculateLocalTransform());
        node.isAnimated = true;
        var bounds = new BoundingBox().inf();
        for (Vector3 point : points) { bounds.ext(point); }
        var center = bounds.getCenter(new Vector3());
        // A meeple figure's printed side faces its +Y.
        var front = new Vector3(Vector3.Y).rot(new Matrix4(instance.transform).mul(node.globalTransform));
        front.z = 0;
        front.nor();
        var knocked = away == null ? new Vector3(front).scl(-1) : new Vector3(away);
        float turn = MathUtils.atan2(front.x * -knocked.y - front.y * -knocked.x, front.x * -knocked.x + front.y * -knocked.y)
              * MathUtils.radiansToDegrees;
        return new Soldier(node, rest, points, center, bounds.min.z, bounds.getDepth(), knocked, turn);
    }

    /**
     * Advances every casualty by {@code seconds} and poses it, thrown pieces bouncing on {@code ground} under {@code
     * gravity} standard gravities. Once a fallen soldier has lain a moment, and {@link #DEBRIS_SECONDS} after
     * the explosion that threw its pieces, it fades away and goes; the pieces fly on as they fade.
     */
    void advance(float seconds, MeepleDebris.Ground ground, float gravity) {
        for (var iterator = casualties.iterator(); iterator.hasNext();) {
            var casualty = iterator.next();
            float previous = casualty.seconds;
            float time = casualty.seconds += seconds;
            float fade = Math.max(casualty.soldiers.isEmpty() ? 0 : FALL_SECONDS + LIE_SECONDS,
                  casualty.debris.isEmpty() ? 0 : DEBRIS_SECONDS);
            casualty.debris.forEach(debris -> debris.pieces().advance(seconds, ground, gravity));
            if (time >= fade + UnitAttack.FADE_SECONDS) {
                casualty.dispose();
                iterator.remove();
                continue;
            }
            ((GpuUnitShader.Shown) casualty.instance.userData).opacity = 1
                  - UnitAttack.smooth((time - fade) / UnitAttack.FADE_SECONDS);
            if (previous < FALL_SECONDS) { casualty.soldiers.forEach(soldier -> fall(casualty.instance, soldier, time, ground)); }
            casualty.debris.forEach(debris -> debris.pieces().pose());
            casualty.instance.calculateTransforms();
        }
    }

    /** Turned to face the hit, knocked away from it and onto its back, always resting on its lowest point. */
    private static void fall(ModelInstance instance, Soldier soldier, float seconds, MeepleDebris.Ground ground) {
        float progress = MathUtils.clamp(seconds / FALL_SECONDS, 0, 1);
        var turn = new Quaternion(Vector3.Z, soldier.turn() * UnitAttack.smooth(progress / .25f));
        var back = new Vector3(Vector3.Z).crs(soldier.away());
        var pose = new Quaternion(back, 90 * UnitAttack.smooth((progress - .05f) / .8f)).mul(turn);
        float knock = KNOCKBACK * soldier.height() * (1 - (1 - progress) * (1 - progress));
        float hop = THROW * soldier.height() * MathUtils.sin(MathUtils.PI * MathUtils.clamp(progress / .8f, 0, 1));
        var at = new Vector3(soldier.center()).mulAdd(soldier.away(), knock);
        float lowest = Float.POSITIVE_INFINITY, support = Float.NEGATIVE_INFINITY;
        var point = new Vector3();
        for (Vector3 vertex : soldier.points()) {
            pose.transform(point.set(vertex).sub(soldier.center()));
            lowest = Math.min(lowest, point.z);
            float floor = ground.height(at.x + point.x, at.y + point.y, soldier.floor() + MeepleDebris.CELL, true);
            if (Float.isFinite(floor)) { support = Math.max(support, floor - point.z); }
        }
        // The captured pose can be hopping or standing on a slope. Land on the surface under the fallen figure;
        // its original height only anchors the start of the fall, never the final resting position.
        float start = soldier.floor() - lowest;
        at.z = MathUtils.lerp(start, Float.isFinite(support) ? support : start, UnitAttack.smooth(progress)) + hop;
        var motion = new Matrix4().setToTranslation(at).rotate(pose)
              .translate(-soldier.center().x, -soldier.center().y, -soldier.center().z);
        soldier.node().localTransform.set(instance.transform).inv().mul(motion).mul(instance.transform).mul(soldier.rest());
    }

    /** The units the casualties belonged to, whose camouflage textures they still use. */
    List<BoardScene.Unit> units() {
        return casualties.stream().map(casualty -> casualty.unit).toList();
    }

    @Override
    public void dispose() {
        casualties.forEach(Casualty::dispose);
        casualties.clear();
        prepared.clear();
    }
}
