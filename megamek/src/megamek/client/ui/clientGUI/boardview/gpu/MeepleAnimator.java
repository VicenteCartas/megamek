/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;
import java.util.Map;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import megamek.common.ResolvedAttack;
import megamek.common.units.EntityMovementMode;
import megamek.common.units.EntityMovementType;

/** Rigid token animation only. Samples the existing movement/combat clocks; never advances a second timeline. */
final class MeepleAnimator {
    private MeepleAnimator() { }

    static UnitMotion.Posture posture(BoardScene.Unit unit, UnitMotion.Sample motion) {
        if (motion.posture() != null) { return motion.posture(); }
        var cause = motion.moving() ? motion.proneCause() : unit.location().proneCause();
        if (cause == null && unit.model() != null && unit.model().state() != null) {
            cause = unit.model().state().pose().proneCause();
        }
        boolean hullDown = unit.location().hullDown() != null ? unit.location().hullDown()
              : unit.model() != null && unit.model().state() != null && UnitAnimator.hullDown(unit);
        return UnitMotion.Posture.of(cause, unit.location().fallSide(), hullDown);
    }

    static EntityMovementMode movement(BoardScene.Unit unit) {
        var form = UnitConversion.form(unit);
        return form != null ? form.movement() : unit.model() == null || unit.model().state() == null
              ? EntityMovementMode.NONE : unit.model().state().structure().movement();
    }

    private static boolean walker(BoardScene.Unit unit) {
        return switch (movement(unit)) {
            case BIPED, TRIPOD, QUAD, INF_LEG, INF_JUMP, INF_UMU, BIPED_SWIM, QUAD_SWIM -> true;
            default -> false;
        };
    }

    static float standingHeight(BoardScene.Unit unit) {
        var anatomy = unit.model() == null || unit.model().state() == null ? null : unit.model().state().structure().anatomy();
        var form = UnitConversion.form(unit);
        // Converted LAMs and vehicle-mode QuadVees occupy one level, even though their construction is still a Mek.
        if (form != null && form.mode() != 0) { return 1; }
        return anatomy == null ? unit.height() : anatomy.superHeavy() ? 3 : 2;
    }

    /**
     * A Mek whose miniature is on the {@link MiniatureHeights} list stands at that miniature's height, measured against
     * the assault meeple; any other unit keeps its family and weight class height. Superheavies keep theirs too: they
     * already stand three levels tall, and the list's ruler is the two-level assault.
     */
    static float meepleHeightScale(BoardScene.Unit unit, UnitFamilyScale family) {
        var anatomy = unit.model() == null || unit.model().state() == null ? null : unit.model().state().structure().anatomy();
        if (anatomy == null || anatomy.miniatureHeightScale() == null || anatomy.superHeavy()) {
            return family.meepleHeightScale();
        }
        return UnitFamilyScale.MEK_ASSAULT.meepleHeightScale() * anatomy.miniatureHeightScale();
    }

    static float bounce(UnitMotion.Sample motion) {
        if (!motion.moving() || (motion.type() != EntityMovementType.MOVE_WALK
              && motion.type() != EntityMovementType.MOVE_RUN && motion.type() != EntityMovementType.MOVE_SPRINT)) { return 0; }
        float envelope = MathUtils.clamp(Math.min(motion.progress(), 1 - motion.progress()) * 12, 0, 1);
        return Math.abs(MathUtils.sin(motion.steps() * MathUtils.PI2 * 2)) * envelope;
    }

    static Vector3 place(GpuUnitModel model, ModelInstance instance, BoardScene.Unit unit, UnitMotion.Sample motion,
          Camera camera, Vector3 ground, float facing) {
        return place(model, instance, unit, motion, camera, ground, facing, null, false);
    }

    static Vector3 place(GpuUnitModel model, ModelInstance instance, BoardScene.Unit unit, UnitMotion.Sample motion,
          Camera camera, Vector3 ground, float facing, UnitConversion conversion, boolean topView) {
        // Whole-token passengers use this root too. Restore it before every pose, including after dismounting.
        var root = instance.getNode(MeepleVisual.ROOT);
        root.translation.setZero();
        root.rotation.idt();
        root.scale.set(1, 1, 1);
        instance.calculateTransforms();
        var posture = posture(unit, motion);
        // A prone snapshot reports occupied height, not the token's standing shape. Recover the Mek's captured size.
        float standing = standingHeight(unit);
        boolean converting = conversion != null && conversion.event.entityId() == unit.id();
        if (converting) {
            standing = MathUtils.lerp(standingHeight(conversion.event.before()), standingHeight(conversion.event.after()), conversion.progress());
        }
        float crouch = Math.max(posture.crouch(), posture.kneel());
        // Overhead, keep the artwork readable at prone height. The existing PRONE label conveys the state.
        if (topView) { crouch = Math.max(crouch, posture.fallen()); }
        float height = MathUtils.lerp(standing, 1, crouch);
        float horizontal = unit.part() >= 0 ? 1 : BoardGeometry.unitScale();
        var position = ground.cpy();
        if (unit.part() < 0 && unit.footprint().size() > 1) {
            var layout = UnitFootprint.layout(unit.location().coords(), unit.footprint(), unit.location().facing() * 60);
            var bounds = UnitBounds.local(instance);
            horizontal = Math.min(layout.width() / bounds.getWidth(), layout.depth() / bounds.getHeight()) * BoardGeometry.multiHexUnitScale();
            position.add(new Vector3(layout.offsetX(), layout.offsetY(), 0).rotate(Vector3.Z, unit.location().facing() * 60 - facing));
        }
        var family = unit.model() == null || unit.model().state() == null ? UnitFamilyScale.DEFAULT
              : unit.model().state().structure().family().forUnit(unit);
        horizontal *= family.meepleScale();
        float vertical = height * BoardGeometry.level() * BoardGeometry.unitHeightScale() / MeepleVisual.HEIGHT
              * family.meepleScale() * meepleHeightScale(unit, family);
        // A fall tips the token over onto the side it fell on, turned only as the posture says (UnitMotion.Posture#turn):
        // it never spins. It gets up as an articulated body does. Overhead it stands, readable.
        float fall = topView ? 0 : posture.fallen();
        var orientation = fall == 0 ? new Quaternion(Vector3.Z, -facing)
              : posture.rising() ? new Quaternion(Vector3.Z, -facing).mul(UnitMotion.Posture.rising(posture.side(), posture.progress()))
              : new Quaternion(Vector3.Z, -facing + posture.turn()).mul(UnitMotion.Posture.tip(posture.side(), 90 * fall));
        float pitch = 0;
        float upright = (1 - fall) * (1 - crouch);
        float lift = motion.airborne(unit) || !walker(unit) ? 0 : bounce(motion) * BoardGeometry.level() * .13f * upright;
        float bank = 0;
        if (motion.moving() && motion.type() != EntityMovementType.MOVE_NONE && !walker(unit)) {
            float envelope = MathUtils.clamp(Math.min(motion.progress(), 1 - motion.progress()) * 12, 0, 1) * upright;
            bank = motion.airborne(unit) ? -MathUtils.clamp(motion.turn(), -12, 12) * envelope
                  : MathUtils.sin(motion.steps() * MathUtils.PI2) * 1.5f * envelope;
        }
        if (motion.moving() && motion.type() == EntityMovementType.MOVE_NONE && motion.steps() > 0) {
            // Shoved into another hex, the token staggers there: it rocks over the way it goes and bounces on its base.
            float stagger = MathUtils.sin(motion.progress() * MathUtils.PI) * upright;
            pitch -= motion.forward() * 14 * stagger;
            bank += motion.lateral() * 14 * stagger;
            lift += stagger * BoardGeometry.level() * .15f;
        }
        if (motion.jets() != null) { pitch -= motion.jets().tilt() * .35f; }
        if (converting) { pitch -= conversion.fold() * 12; }
        instance.transform.set(position, orientation)
              .rotate(Vector3.X, pitch).rotate(Vector3.Y, bank).scale(horizontal, horizontal, vertical);
        support(instance, position.z + .5f + lift);
        return model.anchor(instance, camera);
    }

    /**
     * Apply once after all units have their base pose, so contacts see the current target in either camera. A {@code
     * ghost} of a destroyed unit stands.
     */
    static void attacks(BoardScene.Unit unit, ModelInstance instance, List<UnitAttack> attacks,
          Map<String, ModelInstance> instances, boolean ghost) {
        float recoil = 0, impact = 0;
        var posture = posture(unit, UnitMotion.Sample.STILL);
        boolean prone = posture.crouch() > 0 || posture.fallen() > 0 || posture.kneel() > 0;
        float death = unit.model() != null && unit.model().state() != null && unit.model().state().pose().dead() ? 1 : 0;
        var rest = UnitBounds.world(instance);
        var middle = rest.getCenter(new Vector3());
        // Horizontal, away from the attackers of the hits landing now, each by how hard it lands.
        var away = new Vector3();
        for (var attack : attacks) {
            if (attack.event.target() != null && attack.event.target().id() == unit.id()) {
                impact = Math.max(impact, attack.impact());
                var attacker = attack.event.attacker();
                var from = UnitAttack.center(instances.get(attacker.id() + ":" + attacker.part()), attacker.location(), new Vector3());
                away.mulAdd(new Vector3(middle.x - from.x, middle.y - from.y, 0).nor(), attack.impact());
                instance.transform.trn(attack.dodgeOffset(new Vector3()));
            }
            if (attack.event.entityId() != unit.id()) { continue; }
            if (attack.shot()) { recoil = Math.max(recoil, attack.recoil()); continue; }
            if (attack.death()) { death = attack.deathProgress(); continue; }
            if (prone) { continue; }
            if (attack.removal()) {
                tilt(instance, 0, MathUtils.sin(attack.seconds * 18) * attack.windup() * 9, .5f);
                continue;
            }
            var target = attack.event.target();
            var victim = target == null ? null : instances.get(target.id() + ":" + target.part());
            var center = UnitBounds.world(instance).getCenter(new Vector3());
            var endpoint = UnitAttack.center(victim, attack.event.destination(), new Vector3());
            if (attack.approach == null) {
                var direction = endpoint.cpy().sub(center); direction.z = 0;
                float radius = Math.max(UnitBounds.world(instance).getWidth(), UnitBounds.world(instance).getHeight()) * .5f;
                float other = victim == null ? 0 : Math.max(UnitBounds.world(victim).getWidth(), UnitBounds.world(victim).getHeight()) * .5f;
                // A death from above comes down on its target; the other attacks stop at its side.
                attack.approach = direction.cpy().nor().scl(attack.dfa() ? direction.len() : Math.max(0, direction.len() - radius - other))
                      .limit(BoardGeometry.height() * UnitAttack.PHYSICAL_APPROACH_HEXES);
                float heading = MathUtils.atan2(direction.x, direction.y) * MathUtils.radiansToDegrees;
                attack.approachTurn = ((heading - unit.location().facing() * 60 + 540) % 360) - 180;
            }
            instance.transform.trn(attack.approach.cpy().scl(attack.approachWeight()));
            attack.contact(victim, UnitBounds.world(instance).getCenter(new Vector3()), new Vector3());
            float travel = attack.travelProgress();
            float hop = Math.abs(MathUtils.sin(travel * MathUtils.PI * 4))
                  * MathUtils.clamp(Math.min(travel, 1 - travel) * 10, 0, 1) * BoardGeometry.level() * .1f;
            turn(instance, -attack.approachTurn * attack.approachFacingWeight());
            boolean kick = attack.event.result().kind() == ResolvedAttack.Kind.KICK;
            boolean punch = attack.event.result().kind() == ResolvedAttack.Kind.PUNCH;
            boolean club = attack.event.result().kind() == ResolvedAttack.Kind.CLUB;
            float swing = punch ? (attack.event.result().limb() == megamek.common.units.Mek.LOC_LEFT_ARM ? 1 : -1)
                  * 18 * (attack.windup() - 1.7f * attack.strike()) : 0;
            if (club && !attack.thrust()) { swing = 38 * (attack.windup() - 1.7f * attack.strike()); }
            // A charge or a ram slams in leaning from its feet, its whole body behind the blow; a death from above leaps
            // and comes down feet first, on the target's top when it hits.
            boolean slam = attack.charge() || attack.ram();
            float lean = attack.dfa() ? 20 * attack.windup() : (kick ? 24 : club ? -25 : slam ? -24 : -16) * attack.strike();
            tilt(instance, lean, swing, kick || slam ? 0 : .5f);
            if (club && attack.thrust()) {
                var forward = new Vector3(0, 1, 0).rot(instance.transform); forward.z = 0;
                instance.transform.trn(forward.nor().scl(BoardGeometry.level() * .2f * attack.strike()));
            }
            instance.transform.trn(0, 0, attack.dfa() ? attack.leap(attack.landing(rest.min.z, victim)) : hop);
        }
        if (!prone) {
            tilt(instance, 3 * recoil, 0, .3f);
            // A hit rocks the token away from where it came from; one from overhead, back.
            if (away.isZero(1e-4f)) { tilt(instance, 5 * impact, 0, .3f); }
            else { tilt(instance, new Vector3(-away.y, away.x, 0).nor(), 5 * impact, 0, .3f); }
        }
        if (!ghost && death > 0 && posture(unit, UnitMotion.Sample.STILL).fallen() == 0) { tilt(instance, -80 * death, 0, 0); }
    }

    private static void turn(ModelInstance instance, float degrees) {
        var position = instance.transform.getTranslation(new Vector3());
        instance.transform.trn(position.cpy().scl(-1));
        instance.transform.mulLeft(new com.badlogic.gdx.math.Matrix4().setToRotation(Vector3.Z, degrees));
        instance.transform.trn(position);
    }

    /** Rotate the solid in world units, avoiding shear from its different horizontal/vertical scales; pitch tips it back. */
    private static void tilt(ModelInstance instance, float pitch, float swing, float pivotFraction) {
        if (pitch == 0 && swing == 0) { return; }
        var forward = new Vector3(0, 1, 0).rot(instance.transform); forward.z = 0; forward.nor();
        tilt(instance, new Vector3(forward.y, -forward.x, 0), pitch, swing, pivotFraction);
    }

    /** {@link #tilt(ModelInstance, float, float, float)} about a horizontal world axis: pitch tips the top to its right. */
    private static void tilt(ModelInstance instance, Vector3 axis, float pitch, float swing, float pivotFraction) {
        if (pitch == 0 && swing == 0) { return; }
        var bounds = UnitBounds.world(instance);
        float floor = bounds.min.z;
        var pivot = bounds.getCenter(new Vector3());
        pivot.z = bounds.min.z + bounds.getDepth() * pivotFraction;
        var change = new com.badlogic.gdx.math.Matrix4().setToTranslation(pivot)
              .rotate(Vector3.Z, swing).rotate(axis, pitch).translate(-pivot.x, -pivot.y, -pivot.z);
        instance.transform.mulLeft(change);
        float below = floor - UnitBounds.world(instance).min.z;
        if (below > 0) { instance.transform.trn(0, 0, below); }
    }

    private static void support(ModelInstance instance, float floor) {
        instance.transform.trn(0, 0, floor - UnitBounds.world(instance).min.z);
    }
}
