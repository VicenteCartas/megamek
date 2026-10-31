/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;

/**
 * Meeples dying where they stood, after the view stopped drawing them for their unit: a destroyed unit's meeple and a
 * formation's lost vehicle explode into pieces ({@link MeepleShatter}, {@link MeepleDebris}); a formation's lost
 * soldier turns to the hit, is knocked back and falls on its back. Once all is still they lie a moment, then sink into
 * the ground and are gone: a wreck has no gameplay. View-owned and presentation only.
 */
final class MeepleCasualties implements Disposable {
    static final float FALL_SECONDS = .9f;
    static final float LIE_SECONDS = 1.5f;
    static final float SINK_SECONDS = .8f;
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
        private final List<Soldier> soldiers = new ArrayList<>();
        private final List<Debris> debris = new ArrayList<>();
        private float seconds;

        private Casualty(GpuUnitInstance instance, BoardScene.Unit unit) {
            this.instance = instance;
            this.unit = unit;
        }

        @Override
        public void dispose() { debris.forEach(pieces -> pieces.model().dispose()); }
    }

    /**
     * A fallen soldier: its node's local transform when it fell, its rest bounds' world corners and their centre, the
     * ground under it, its height, the way it is knocked and the turn that makes it face the hit.
     */
    private record Soldier(Node node, Matrix4 rest, Vector3[] corners, Vector3 center, float floor, float height,
          Vector3 away, float turn) { }

    /** Thrown pieces and the model that owns their meshes. */
    private record Debris(Model model, MeepleDebris pieces) { }

    private final List<Casualty> casualties = new ArrayList<>();

    List<Casualty> casualties() { return casualties; }

    /**
     * Blows up a destroyed unit's meeple.
     *
     * @param standing the meeple as the view last drew it, which it no longer draws
     * @param hit      horizontal, away from the hits, as long as the damage they dealt; null to burst out evenly
     */
    void exploded(GpuUnitInstance standing, BoardScene.Unit unit, Vector3 hit) {
        Node meeple = standing.getNode(MeepleVisual.ROOT);
        if (meeple == null) { return; }
        var casualty = new Casualty(standing, unit);
        standing.nodes.clear();
        shatter(casualty, meeple, TOKEN_CUTS, unit.id(), hit);
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
        var middle = previous.transform.getTranslation(new Vector3());
        var casualty = new Casualty(previous, unit);
        previous.nodes.clear();
        for (Node node : lost) {
            var rig = was.rigs().stream().filter(candidate -> node.id.equals(candidate.container())).findFirst().orElseThrow();
            var outward = UnitBounds.subtree(node).mul(previous.transform).getCenter(new Vector3()).sub(middle);
            outward.z = 0;
            var away = hit != null ? new Vector3(hit).nor() : outward.len2() < 1e-4f ? null : outward.nor();
            Node drawn = rig.transport() ? drawn(node) : null;
            if (drawn == null) {
                previous.nodes.add(node);
                casualty.soldiers.add(soldier(previous, node, away));
            } else {
                shatter(casualty, drawn, VEHICLE_CUTS, unit.id() * 31 + node.id.hashCode(), hit);
            }
        }
        previous.calculateTransforms();
        casualties.add(casualty);
    }

    /** Breaks {@code drawn}, a node of the casualty's instance, into pieces in its place and throws them. */
    private static void shatter(Casualty casualty, Node drawn, int cuts, int seed, Vector3 hit) {
        // Cut in the mesh's own coordinates, where its camouflage was projected, about the board's up there.
        var frame = new Matrix4(casualty.instance.transform).mul(drawn.globalTransform);
        var up = new Vector3(Vector3.Z).rot(new Matrix4(frame).inv());
        Model model = MeepleShatter.build(drawn, up, cuts, seed, drawn.id);
        Node pieces = model.nodes.first().copy();
        pieces.isAnimated = true;
        pieces.localTransform.set(drawn.globalTransform);
        casualty.instance.nodes.add(pieces);
        casualty.debris.add(new Debris(model, new MeepleDebris(casualty.instance, pieces, hit, seed)));
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

    private static Soldier soldier(ModelInstance instance, Node node, Vector3 away) {
        var rest = new Matrix4(node.calculateLocalTransform());
        node.isAnimated = true;
        var corners = MeepleShatter.corners(UnitBounds.subtree(node), instance.transform);
        var center = new Vector3();
        float floor = Float.POSITIVE_INFINITY, top = Float.NEGATIVE_INFINITY;
        for (Vector3 corner : corners) {
            center.mulAdd(corner, 1f / corners.length);
            floor = Math.min(floor, corner.z);
            top = Math.max(top, corner.z);
        }
        // A meeple figure's printed side faces its +Y.
        var front = new Vector3(Vector3.Y).rot(new Matrix4(instance.transform).mul(node.globalTransform));
        front.z = 0;
        front.nor();
        var knocked = away == null ? new Vector3(front).scl(-1) : new Vector3(away);
        float turn = MathUtils.atan2(front.x * -knocked.y - front.y * -knocked.x, front.x * -knocked.x + front.y * -knocked.y)
              * MathUtils.radiansToDegrees;
        return new Soldier(node, rest, corners, center, floor, top - floor, knocked, turn);
    }

    /**
     * Advances every casualty by {@code seconds} and poses it, thrown pieces bouncing on {@code ground} under {@code
     * gravity} standard gravities. Once everything of it is still, it lies, sinks and goes.
     */
    void advance(float seconds, MeepleDebris.Ground ground, float gravity) {
        for (var iterator = casualties.iterator(); iterator.hasNext();) {
            var casualty = iterator.next();
            float time = casualty.seconds += seconds;
            float still = casualty.soldiers.isEmpty() ? 0 : FALL_SECONDS;
            for (Debris debris : casualty.debris) {
                debris.pieces().advance(seconds, ground, gravity);
                // NaN while any piece still moves.
                still = Math.max(still, debris.pieces().settled());
            }
            if (time >= still + LIE_SECONDS + SINK_SECONDS) {
                casualty.dispose();
                iterator.remove();
                continue;
            }
            float sinking = Float.isNaN(still) ? 0 : UnitAttack.smooth((time - still - LIE_SECONDS) / SINK_SECONDS);
            casualty.soldiers.forEach(soldier -> fall(casualty.instance, soldier, time, sinking * soldier.height()));
            casualty.debris.forEach(debris -> debris.pieces().pose(sinking * debris.pieces().depth));
            casualty.instance.calculateTransforms();
        }
    }

    /** Turned to face the hit, knocked away from it and onto its back, always resting on its lowest point. */
    private static void fall(ModelInstance instance, Soldier soldier, float seconds, float sink) {
        float progress = MathUtils.clamp(seconds / FALL_SECONDS, 0, 1);
        var turn = new Quaternion(Vector3.Z, soldier.turn() * UnitAttack.smooth(progress / .25f));
        var back = new Vector3(Vector3.Z).crs(soldier.away());
        var pose = new Quaternion(back, 90 * UnitAttack.smooth((progress - .05f) / .8f)).mul(turn);
        float knock = KNOCKBACK * soldier.height() * (1 - (1 - progress) * (1 - progress));
        float hop = THROW * soldier.height() * MathUtils.sin(MathUtils.PI * MathUtils.clamp(progress / .8f, 0, 1));
        var at = new Vector3(soldier.center()).mulAdd(soldier.away(), knock);
        at.z = soldier.floor() - MeepleShatter.lowest(soldier.corners(), soldier.center(), pose) + hop - sink;
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
    }
}
