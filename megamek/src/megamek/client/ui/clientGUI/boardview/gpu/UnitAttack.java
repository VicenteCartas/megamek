/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.ResolvedAttack;
import megamek.common.equipment.AmmoType;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.WeaponType;
import megamek.common.units.UnitLocation;

/** A resolved attack's presentation clock. Outcomes and damage are never calculated here. */
final class UnitAttack {
    static final float ANTICIPATION_SECONDS = .2f;
    /**
     * An Arrow IV round flies in slower than a shell, so its missile is seen coming: this much longer per hex of range,
     * between these bounds. A shell takes at most .7 seconds.
     */
    static final float ARROW_IV_SECONDS_PER_HEX = .08f;
    static final float ARROW_IV_MIN_FLIGHT_SECONDS = 1.12f;
    static final float ARROW_IV_MAX_FLIGHT_SECONDS = 1.76f;
    static final float RECOVERY_SECONDS = .35f;
    /** How much longer a TAG that hits holds its designator on the target, so the player can see what it marked. */
    static final float DESIGNATOR_HOLD_SECONDS = 1.2f;
    static final float RECOIL_KICK_SECONDS = .035f;
    static final float RECOIL_RECOVERY_SECONDS = .24f;
    static final float RECOIL_DISTANCE = .9f;
    /** Rotary barrels run at about this speed through a burst; slow enough that a turn never reads as backwards. */
    static final float SPIN_TURNS_PER_SECOND = 1.5f;
    static final float PPC_RECOIL_SCALE = 1.5f;
    static final float DEATH_SECONDS = 1.2f;
    static final float MELEE_WALK_SECONDS = 1.05f;
    static final float MELEE_RUN_SECONDS = .65f;
    static final float MELEE_SWING_SECONDS = .4f;
    static final float MELEE_DODGE_HEXES = .14f;
    static final float PHYSICAL_APPROACH_HEXES = .9f;
    static final int MACHINE_GUN_ROUNDS = 6;
    static final float MACHINE_GUN_FIRE_SECONDS = .55f;
    final BoardScene.Combat event;
    final float flightSeconds;
    final float contactSeconds;
    final float duration;
    private final float firingEndSeconds;
    private final Map<Integer, Set<Integer>> firingMounts = new HashMap<>();
    private final Map<Long, ResolvedAttack.Shot> profiles = new HashMap<>();
    private Vector3 physicalContact;
    private Vector3 contactCenter;
    Vector3 approach;
    float approachTurn;
    final float approachSeconds;
    java.util.function.Function<Ray, BoardGeometry.Hit> landscape;
    java.util.function.BiFunction<Ray, Float, BoardGeometry.Hit> landscapeSegment;
    private final Map<Integer, Vector3> misses = new HashMap<>();
    /** Where a round the server placed came down, on the installed terrain; {@code null} until first found. */
    private Vector3 placedLanding;
    private final Map<Vector3, Boolean> hardImpacts = new HashMap<>();
    private final Map<Integer, Vector3> beamMisses = new HashMap<>();
    private final Map<Vector3, Contact> landscapeContacts = new HashMap<>();
    /** Captured cosmetic contact. Progress belongs to the original flight curve, which is never bent after a hit. */
    record Contact(Vector3 point, Vector3 incoming, BoardGeometry.Hit surface, float progress) { }
    private UnitPicking surfaces;
    private record HitPoint(ModelInstance target, String location, int seed) { }
    private final Map<HitPoint, UnitPicking.SurfacePoint> hitPoints = new HashMap<>();
    float seconds;
    float delay;
    float receivedAt;
    float jitter;
    UnitVolley.Group group;
    UnitAttack incoming;
    /** The hit that caused this death, when the death directly follows it: the death plays from its landing. */
    UnitAttack cause;

    UnitAttack(BoardScene.Combat event) {
        this.event = event;
        event.result().mounts().forEach(mount -> firingMounts
              .computeIfAbsent(mount.entityId(), ignored -> new HashSet<>()).add(mount.equipmentIndex()));
        event.result().mounts().forEach(mount -> {
            if (mount.shot() != null) { profiles.put(mountKey(mount.entityId(), mount.equipmentIndex()), mount.shot()); }
        });
        float distance = BoardGeometry.center(event.attacker().location().coords(), event.attacker().location().elevation())
              .dst(BoardGeometry.center(event.destination().coords(), event.destination().elevation())) / BoardGeometry.height();
        approachSeconds = event.result().kind() == ResolvedAttack.Kind.PUSH ? MELEE_RUN_SECONDS : MELEE_WALK_SECONDS;
        flightSeconds = firesArrowIV(event.result())
              ? MathUtils.clamp(distance * ARROW_IV_SECONDS_PER_HEX, ARROW_IV_MIN_FLIGHT_SECONDS,
                    ARROW_IV_MAX_FLIGHT_SECONDS)
              : MathUtils.clamp(distance * .05f, interception() == null ? .18f : .45f, .7f);
        float firingSeconds = machineGunFireSeconds(event.result().shot());
        for (var profile : profiles.values()) { firingSeconds = Math.max(firingSeconds, machineGunFireSeconds(profile)); }
        contactSeconds = removal() ? .85f : death() ? DEATH_SECONDS : shot() ? ANTICIPATION_SECONDS + flightSeconds + firingSeconds
              : approachSeconds + MELEE_SWING_SECONDS;
        duration = removal() ? 1.4f : death() ? DEATH_SECONDS : contactSeconds + RECOVERY_SECONDS + (shot() ? 0 : MELEE_RUN_SECONDS)
              + (designatorHolds() ? DESIGNATOR_HOLD_SECONDS : 0) + fuelAirIgnitionHold();
        firingEndSeconds = event.result().mounts().stream()
              .anyMatch(mount -> "flame".equals(effect(mount.entityId(), mount.equipmentIndex())))
              ? Math.max(contactSeconds, ANTICIPATION_SECONDS + flightSeconds + RECOVERY_SECONDS) : contactSeconds;
    }

    boolean shot() {
        return event.result().kind() == ResolvedAttack.Kind.SHOT;
    }

    /**
     * Whether the attack is an Arrow IV round: any shot from an Arrow IV launcher, or an aircraft's Arrow IV homing
     * missile, which is the same round.
     */
    static boolean firesArrowIV(ResolvedAttack result) {
        String weaponName = result.equipmentName();
        if ((weaponName == null) || !(EquipmentType.get(weaponName) instanceof WeaponType weapon)) {
            return false;
        }
        AmmoType.AmmoTypeEnum ammoType = weapon.getAmmoType();
        return (ammoType == AmmoType.AmmoTypeEnum.ARROW_IV) || (ammoType == AmmoType.AmmoTypeEnum.ARROW_IV_BOMB);
    }

    /** Whether this is an artillery round still on its way: fired, and not yet landed on the hex it is coming down on. */
    boolean artilleryIncoming() {
        ResolvedAttack.Shot profile = event.result().shot();
        boolean isArtillery = shot() && (profile != null) && profile.artillery();
        return isArtillery && (seconds < contactSeconds);
    }

    boolean death() { return event.result().kind() == ResolvedAttack.Kind.DEATH; }

    boolean removal() {
        return event.result().kind() == ResolvedAttack.Kind.BRUSH_OFF || event.result().kind() == ResolvedAttack.Kind.SHAKE_OFF;
    }

    boolean defensive() { return event.result().shot() != null && event.result().shot().defensive(); }

    ResolvedAttack.Interception interception() {
        return event.result().shot() == null ? null : event.result().shot().interception();
    }

    float deathProgress() { return death() ? smooth(seconds / DEATH_SECONDS) : 0; }

    /** Whether this is a shot that hits, from {@code lead} seconds before its first round lands until its last does. */
    boolean landing(float lead) {
        return shot() && event.result().hit() && !defensive()
              && seconds >= ANTICIPATION_SECONDS + flightSeconds - lead && seconds < contactSeconds;
    }

    boolean fires(UnitEquipmentAssembly.Binding binding) {
        int owner = binding.memberId() < 0 ? event.attacker().id() : binding.memberId();
        return fires(owner, binding.index());
    }

    boolean fires(int owner, int index) {
        var state = event.attacker().model() == null ? null : event.attacker().model().state();
        var appearance = state == null ? null : state.appearance();
        if (appearance != null && owner != event.entityId()) { appearance = appearance.fighters().get(owner); }
        return firingMounts.getOrDefault(owner, Set.of()).contains(index)
              && (appearance == null || !appearance.inoperableEquipment().contains(index));
    }

    /** The same visible posed endpoint is used for aiming and effects in either camera. */
    static Vector3 center(ModelInstance instance, BoardScene.Waypoint location, Vector3 result) {
        return instance == null ? result.set(BoardGeometry.center(location.coords(), location.elevation()))
              .add(0, 0, BoardGeometry.level() * .5f) : UnitBounds.world(instance).getCenter(result);
    }

    /** A cosmetic miss must clear the posed target, including large hulls; it never changes the resolved outcome. */
    Vector3 endpoint(ModelInstance targetInstance, Vector3 origin, Vector3 result) {
        if (!shot() && physicalContact != null) { return result.set(physicalContact); }
        return endpoint(targetInstance, origin, !event.result().hit(), 0, result);
    }

    /** Use the same posed mesh picker as the board. This is a contact location, never another hit decision. */
    Vector3 contact(ModelInstance target, Vector3 origin, Vector3 result) {
        if (surfaces == null) { surfaces = new UnitPicking(); }
        return contact(target, origin, surfaces, result);
    }

    Vector3 contact(ModelInstance target, Vector3 origin, UnitPicking picking, Vector3 result) {
        if (physicalContact != null) {
            result.set(physicalContact);
            if (event.result().kind() == ResolvedAttack.Kind.PUSH && event.result().hit() && contactCenter != null && target != null) {
                result.add(UnitBounds.world(target).getCenter(new Vector3()).sub(contactCenter));
            }
            return result;
        }
        // A missed swing aims at the original stance; the defender steps away on the same clock.
        endpoint(target, origin, false, 0, result);
        if (event.result().hit() && target != null && !event.result().impacts().isEmpty()) {
            hitEndpoint(target, origin, event.result().limb(), 0, 1, result);
        } else if (target != null) {
            var bounds = UnitBounds.world(target);
            var center = bounds.getCenter(new Vector3());
            float height = event.result().kind() == ResolvedAttack.Kind.KICK ? .18f : .65f;
            var side = new Vector3(center.y - origin.y, origin.x - center.x, 0).nor();
            for (float offset : new float[] { 0, -.22f, .22f }) {
                var point = center.cpy().mulAdd(side, offset * Math.max(bounds.getWidth(), bounds.getHeight()));
                point.z = bounds.min.z + bounds.getDepth() * height;
                var ray = new Ray(origin, point.sub(origin).nor());
                float distance = picking.distance(target, ray);
                if (Float.isFinite(distance)) {
                    result.set(origin).mulAdd(ray.direction, (float) Math.sqrt(distance));
                    break;
                }
            }
        }
        if (target != null && !event.result().hit()) { result.sub(dodgeOffset(new Vector3())); }
        physicalContact = result.cpy();
        if (target != null) { contactCenter = UnitBounds.world(target).getCenter(new Vector3()); }
        return result;
    }

    /**
     * Where a round the server placed came down. The server gives a hex and a whole level; the installed terrain is
     * sculpted, so its ground sits above or below that level within the hex. The point is dropped onto the terrain, or
     * a crater set at the flat level would float or sink and never be painted.
     */
    private Vector3 placedLanding(UnitLocation landing) {
        if (placedLanding != null) {
            return placedLanding;
        }
        Vector3 flat = BoardGeometry.center(landing.coords(), landing.elevation());
        // Until the attack is placed on the terrain there is nothing to drop onto; ask again then.
        if (landscape == null) {
            return flat;
        }
        Vector3 ground = GpuArtilleryBlast.terrain(landscape, flat.x, flat.y, flat.z);
        placedLanding = (ground == null) ? flat : ground;
        return placedLanding;
    }

    Vector3 endpoint(ModelInstance targetInstance, Vector3 origin, boolean missed, int missile, Vector3 result) {
        var profile = event.result().shot();
        if (profile != null && profile.impact() != null) {
            return result.set(placedLanding(profile.impact()));
        }
        if (defensive()) {
            if (incoming != null) {
                var launch = center(targetInstance, incoming.event.attacker().location(), new Vector3());
                center(null, incoming.event.destination(), result);
                return projectile(launch, result.cpy(), interceptProgress(launch, result),
                      incoming.event.result().shot().indirect(), result);
            }
            // Point defense aims into the incoming approach, never at the launcher's hull.
            center(targetInstance, event.destination(), result);
            return result.set(origin.cpy().lerp(result, .25f)).add(0, 0, BoardGeometry.level() * .35f);
        }
        center(targetInstance, event.destination(), result);
        if (!missed) { return shot() ? hitEndpoint(targetInstance, origin, missile, 0, 1, result) : result; }
        if (misses.containsKey(missile)) { return result.set(misses.get(missile)); }
        scatter(targetInstance, origin, missile, false, result);
        if (landscape != null) { misses.put(missile, result.cpy()); }
        return result;
    }

    /** Cosmetic placement on engine-reported locations. Unknown/unsplit locations use the actual hull surface. */
    Vector3 hitEndpoint(ModelInstance target, Vector3 origin, int seed, int ordinal, int count, Vector3 result) {
        center(target, event.destination(), result);
        if (target == null) { return result; }
        String location = "*";
        int weight = event.result().impacts().stream().mapToInt(impact -> Math.max(0, impact.weight())).sum();
        float at = (ordinal + .5f) / Math.max(1, count) * weight;
        for (var impact : event.result().impacts()) {
            at -= Math.max(0, impact.weight());
            if (at < 0) { location = impact.location(); break; }
        }
        if (MeepleVisual.isMeeple(target)) { location = "*"; }
        if (surfaces == null) { surfaces = new UnitPicking(); }
        var key = new HitPoint(target, location, seed);
        var point = hitPoints.computeIfAbsent(key, ignored -> surfaces.surface(target, key.location(), origin,
              seed ^ event.result().id().hashCode()));
        if (point != null) { point.world(target, result); }
        // A concave token can occlude its own incoming-facing wall. Stop at the first surface along the shot.
        if (MeepleVisual.isMeeple(target)) {
            var ray = new Ray(origin, result.cpy().sub(origin).nor());
            float distance = surfaces.distance(target, ray);
            if (Float.isFinite(distance)) { result.set(origin).mulAdd(ray.direction, (float) Math.sqrt(distance)); }
        }
        return result;
    }

    private void scatter(ModelInstance targetInstance, Vector3 origin, int ordinal, boolean beam, Vector3 result) {
        var center = center(targetInstance, event.destination(), new Vector3());
        var bounds = targetInstance == null ? null : UnitBounds.world(targetInstance);
        float radius = bounds == null ? BoardGeometry.height() * .35f : Math.max(bounds.getWidth(), bounds.getHeight()) * .55f;
        var hit = new Vector3();
        int seed = event.result().id().hashCode() ^ ordinal * 7919;
        for (int attempt = 0; attempt < 16; attempt++) {
            float angle = noise(seed + attempt * 31) * MathUtils.PI2;
            float spread = radius + BoardGeometry.height() * (.15f + noise(seed + 137 + attempt) * 1.6f);
            result.set(center).add(MathUtils.cos(angle) * spread, MathUtils.sin(angle) * spread,
                  beam ? (noise(seed + 991) * 2 - 1) * (bounds == null ? BoardGeometry.level() : bounds.getDepth()) : 0);
            if (shot() && !beam) { ground(result); }
            var ray = new Ray(origin, new Vector3(result).sub(origin).nor());
            if (bounds == null || !Intersector.intersectRayBounds(ray, bounds, hit)
                  || (!beam && hit.dst2(origin) > result.dst2(origin))) { break; }
            radius *= 1.15f;
        }
    }

    /** Straight light does not stop at an arbitrary point in the air. False means no visible impact. */
    boolean beamEndpoint(ModelInstance target, Vector3 origin, int ordinal, Vector3 result) {
        if (defensive()) { endpoint(target, origin, result); return true; }
        if (event.result().hit()) { hitEndpoint(target, origin, ordinal, 0, 1, result); return true; }
        beamAim(target, origin, ordinal, result);
        var ray = new Ray(origin, result.cpy().sub(origin).nor());
        var hit = landscape == null ? null : landscape.apply(ray);
        result.set(origin).mulAdd(ray.direction, hit == null ? BoardGeometry.height() * 200 : (float) Math.sqrt(hit.distance()));
        if (hit != null) { landscapeContacts.put(result.cpy(), new Contact(result.cpy(), ray.direction.cpy(), hit, 1)); }
        return hit != null;
    }

    /** Resolve a missed flight once against the same installed geometry as beams and picking. */
    Contact landscapeContact(java.util.function.DoubleFunction<Vector3> path, int segments) {
        if (landscape == null) { return null; }
        Vector3 from = path.apply(0);
        for (int segment = 1; segment <= segments; segment++) {
            Vector3 to = path.apply((double) segment / segments);
            Vector3 direction = to.cpy().sub(from);
            float length = direction.len();
            if (length > .0001f) {
                var ray = new Ray(from, direction.scl(1 / length));
                var hit = landscapeSegment == null ? landscape.apply(ray) : landscapeSegment.apply(ray, length);
                if (hit != null && hit.distance() <= length * length + .001f) {
                    float distance = (float) Math.sqrt(hit.distance());
                    Vector3 point = ray.getEndPoint(new Vector3(), distance);
                    var contact = new Contact(point, ray.direction.cpy(), hit,
                          (segment - 1 + Math.min(1, distance / length)) / segments);
                    landscapeContacts.put(point.cpy(), contact);
                    return contact;
                }
            }
            from = to;
        }
        return null;
    }

    Contact landscapeContact(Vector3 point) { return landscapeContacts.get(point); }

    Vector3 beamAim(ModelInstance target, Vector3 origin, int ordinal, Vector3 result) {
        if (beamMisses.containsKey(ordinal)) { return result.set(beamMisses.get(ordinal)); }
        scatter(target, origin, ordinal, true, result);
        if (landscape != null) { beamMisses.put(ordinal, result.cpy()); }
        return result;
    }

    /** Capture the struck material once from the same installed geometry that placed the impact. */
    boolean hardImpact(Vector3 point) {
        Contact contact = landscapeContacts.get(point);
        if (contact != null) { return contact.surface().hardSurface(); }
        if (landscape == null) { return false; }
        Boolean captured = hardImpacts.get(point);
        if (captured != null) { return captured; }
        float offset = BoardGeometry.level() * .1f;
        var ray = new Ray(point.cpy().add(0, 0, offset), new Vector3(0, 0, -1));
        var hit = landscape.apply(ray);
        if (hit == null) { return false; }
        boolean hard = hit.hardSurface() && Math.abs(hit.distance() - offset * offset) < offset * offset * .5f;
        hardImpacts.put(point.cpy(), hard);
        return hard;
    }

    private void ground(Vector3 point) {
        float fallback = event.destination().elevation() * BoardGeometry.level();
        var ray = new Ray(new Vector3(point.x, point.y, Math.max(point.z, fallback) + BoardGeometry.level() * 100), new Vector3(0, 0, -1));
        var hit = landscape == null ? null : landscape.apply(ray);
        point.z = hit == null ? fallback : ray.origin.z - (float) Math.sqrt(hit.distance());
    }

    static float noise(int value) {
        value ^= value >>> 16;
        value *= 0x7feb352d;
        value ^= value >>> 15;
        value *= 0x846ca68b;
        value ^= value >>> 16;
        return (value & 0xFFFFFF) / (float) 0x1000000;
    }

    float aimWeight() {
        if (death()) { return 0; }
        if (!shot()) { return seconds < duration ? 1 : 0; }
        return seconds < ANTICIPATION_SECONDS ? smooth(seconds / ANTICIPATION_SECONDS)
              : 1 - smooth((seconds - contactSeconds) / RECOVERY_SECONDS);
    }

    ResolvedAttack.Shot profile(UnitEquipmentAssembly.Binding binding) {
        return profile(binding.memberId() < 0 ? event.entityId() : binding.memberId(), binding.index());
    }

    ResolvedAttack.Shot profile(int owner, int index) {
        if (owner == event.entityId() && index == event.result().equipmentIndex() && event.result().shot() != null) {
            return event.result().shot();
        }
        return profiles.getOrDefault(mountKey(owner, index), event.result().shot());
    }

    /** Mesh-free effects use the same captured equipment families as model selection. */
    String effect(int owner, int index) {
        var state = event.attacker().model() == null ? null : event.attacker().model().state();
        var structure = state == null ? null : state.structure();
        if (structure != null && owner != event.entityId()) {
            structure = structure.bodyForm() == null ? null : structure.bodyForm().fighters().stream()
                  .filter(member -> member.id() == owner).map(UnitModelState.FlightMember::structure).findFirst().orElse(null);
        }
        String family = structure == null ? "" : structure.equipment().stream().filter(mount -> mount.index() == index)
              .map(megamek.client.ui.tileset.UnitModelEquipment.Mount::family).findFirst().orElse("");
        if (family.isEmpty() && owner == event.entityId() && index == event.result().equipmentIndex()) {
            var type = megamek.common.equipment.EquipmentType.get(event.result().equipmentName());
            if (type != null) { family = megamek.client.ui.tileset.UnitModelEquipment.family(type); }
        }
        var profile = profile(owner, index);
        return switch (family) {
            case "laser", "ppc", "energy", "bomb", "mine" -> family;
            case "flamer" -> "flame";
            case "extinguisher" -> "spray";
            case "screen-launcher" -> "screen";
            case "missile" -> "missile";
            case "infantry-melee" -> "melee";
            case "sensor" -> firesTag(owner, index) ? "designator" : "none";
            default -> profile != null && profile.ppc() ? "ppc" : "bullet";
        };
    }

    /**
     * Whether the mount is the TAG this attack fired, which draws as a laser designator. TAG fires alone, so it is
     * always the attack's own weapon.
     */
    boolean firesTag(int owner, int index) {
        boolean isAttackWeapon = (owner == event.entityId()) && (index == event.result().equipmentIndex());
        return isAttackWeapon && (EquipmentType.get(event.result().equipmentName()) instanceof WeaponType weapon)
              && weapon.hasFlag(WeaponType.F_TAG);
    }

    /** Whether this is a TAG that hit, whose designator stays on the target a little longer. */
    private boolean designatorHolds() {
        return shot() && event.result().hit() && firesTag(event.entityId(), event.result().equipmentIndex());
    }

    /**
     * How much longer a fuel-air round's attack lasts, so the terrain it changes shows at its ignition, before the next
     * attack starts: none for any other attack.
     */
    private float fuelAirIgnitionHold() {
        return shot() ? Math.max(0, terrainDelaySeconds() + .1f - RECOVERY_SECONDS) : 0;
    }

    /**
     * How long after contact the terrain this attack changes shows its change: a fuel-air round's at its ignition, a
     * moment after landing, when the fire that clears and burns it lights; any other attack's at contact.
     */
    float terrainDelaySeconds() {
        return GpuArtilleryBlast.isFuelAir(event.result().shot()) ? GpuArtilleryBlast.FUEL_AIR_IGNITION_SECONDS : 0;
    }

    float firingEndSeconds() { return firingEndSeconds; }

    /** Preserve the server's total. Division across logical group members is cosmetic, never another cluster roll. */
    int missileHits(int owner, int index, int missiles) {
        if (!event.result().hit()) { return 0; }
        var result = event.result();
        var individual = profiles.get(mountKey(owner, index));
        if (individual != null && individual.missileHits() != null) {
            return MathUtils.clamp(individual.missileHits(), 0, missiles);
        }
        if (result.shot() == null || result.shot().missileHits() == null) {
            return missiles - interceptedMissiles(owner, index, missiles);
        }
        return missileShare(owner, index, missiles, result.shot().missileHits());
    }

    int interceptedMissiles(int owner, int index, int missiles) {
        return interception() == null ? 0 : missileShare(owner, index, missiles, interception().missiles());
    }

    private int missileShare(int owner, int index, int missiles, int count) {
        var result = event.result();
        int total = 0, before = 0;
        boolean found = false;
        for (var mount : result.mounts()) {
            var shot = mount.shot() == null ? result.shot() : mount.shot();
            int rack = shot == null ? 0 : shot.missiles();
            if (mount.entityId() == owner && mount.equipmentIndex() == index) { before = total; found = true; }
            total += rack;
        }
        if (!found || total == 0) { return MathUtils.clamp(count, 0, missiles); }
        int hits = MathUtils.clamp(count, 0, total);
        return (int) ((long) hits * (before + missiles) / total - (long) hits * before / total);
    }

    /** The closer of half the approach and two hexes from the defended unit, measured in the board plane. */
    static float interceptProgress(Vector3 origin, Vector3 target) {
        float distance = (float) Math.hypot(target.x - origin.x, target.y - origin.y);
        return Math.max(.5f, 1 - 2 * BoardGeometry.height() / Math.max(.001f, distance));
    }

    float contactFlash() {
        return contactFlash(contactSeconds);
    }

    float contactFlash(float contact) {
        return seconds >= contact ? Math.max(0, 1 - (seconds - contact) / RECOVERY_SECONDS) : 0;
    }

    private static long mountKey(int owner, int index) { return (long) owner << 32 | index & 0xFFFFFFFFL; }

    boolean arcing(UnitEquipmentAssembly.Binding binding) {
        var shot = profile(binding);
        return shot != null && (shot.artillery() || shot.indirect());
    }

    float flight() {
        return MathUtils.clamp((seconds - ANTICIPATION_SECONDS) / flightSeconds, 0, 1);
    }

    static Vector3 projectile(Vector3 origin, Vector3 target, float progress, boolean arcing, Vector3 result) {
        float t = MathUtils.clamp(progress, 0, 1);
        float arc = arcing ? arcHeight(origin, target) : 0;
        return result.set(origin).lerp(target, t).add(0, 0, MathUtils.sin(t * MathUtils.PI) * arc);
    }

    static float arcHeight(Vector3 origin, Vector3 target) {
        return Math.max(BoardGeometry.height() * .4f, origin.dst(target) * .25f);
    }

    float impact() {
        return !death() && !defensive() && event.result().hit() ? contactFlash() : 0;
    }

    float recoil() {
        float strength = profiles.isEmpty() ? recoil(event.result().shot()) : 0;
        for (var profile : profiles.values()) { strength = Math.max(strength, recoil(profile)); }
        return strength;
    }

    float recoil(UnitEquipmentAssembly.Binding binding) { return recoil(profile(binding)); }

    /**
     * A rotary barrel cluster's turn in degrees: it runs up before the first round, turns at full speed through the
     * burst and its recoil, and runs down by the end of the attack, a whole number of turns round, so it stops where
     * it started.
     */
    float spin(UnitEquipmentAssembly.Binding binding) {
        var profile = profile(binding);
        float rise = ANTICIPATION_SECONDS;
        // The run-down always finishes inside the attack, however long the burst.
        float burst = Math.min(duration - .1f,
              rise + roundDelay(roundCount(profile) - 1, profile) + RECOIL_KICK_SECONDS + RECOIL_RECOVERY_SECONDS);
        float fall = duration - burst;
        // Seconds at full speed that the whole run is worth.
        float run = rise / 2 + (burst - rise) + fall / 2;
        float degrees = Math.max(1, Math.round(SPIN_TURNS_PER_SECOND * run)) * 360 / run;
        if (seconds <= 0) { return 0; }
        if (seconds <= rise) { return degrees * seconds * seconds / (2 * rise); }
        if (seconds <= burst) { return degrees * (rise / 2 + seconds - rise); }
        float down = Math.min(seconds - burst, fall);
        return degrees * (rise / 2 + burst - rise + down - down * down / (2 * fall));
    }

    boolean chargingPpc() {
        return shot() && seconds >= 0 && seconds < ANTICIPATION_SECONDS
              && (event.result().shot() != null && event.result().shot().ppc()
                    || profiles.values().stream().anyMatch(ResolvedAttack.Shot::ppc));
    }

    private float recoil(ResolvedAttack.Shot profile) {
        int rounds = roundCount(profile);
        float pulse = 0;
        for (int round = 0; round < rounds; round++) {
            float age = seconds - ANTICIPATION_SECONDS - roundDelay(round, profile);
            if (age < 0) { continue; }
            pulse = Math.max(pulse, age < RECOIL_KICK_SECONDS ? smooth(age / RECOIL_KICK_SECONDS)
                  : 1 - smooth((age - RECOIL_KICK_SECONDS) / RECOIL_RECOVERY_SECONDS));
        }
        return pulse * (profile != null && profile.ppc() ? PPC_RECOIL_SCALE : cannonScale(profile));
    }

    /** One bounded calibre response for kick and muzzle blast; missile rack size never becomes cannon recoil. */
    static float cannonScale(ResolvedAttack.Shot profile) {
        if (profile == null) { return 1; }
        return profile.ballistic() ? (float) Math.sqrt(MathUtils.clamp(profile.rackSize(), 1, 30) / 5f) : 0;
    }

    /** The projectile, recoil and flash of each round share the same firing time. */
    float roundDelay(int round, ResolvedAttack.Shot profile) {
        int rounds = roundCount(profile);
        return profile != null && profile.machineGun() ? round * machineGunFireSeconds(profile) / (rounds - 1)
              : round * Math.min(.03f, flightSeconds / rounds * .3f);
    }

    static int roundCount(ResolvedAttack.Shot profile) {
        return profile == null ? 1 : profile.machineGun() ? MACHINE_GUN_ROUNDS * (profile.rapidFire() ? 3 : 1)
              : MathUtils.clamp(profile.shots(), 1, 16);
    }

    private static float machineGunFireSeconds(ResolvedAttack.Shot profile) {
        return profile != null && profile.machineGun() ? MACHINE_GUN_FIRE_SECONDS * (profile.rapidFire() ? 2 : 1) : 0;
    }

    /** MG tracers keep a constant flight time; other weapons retain their existing shared impact time. */
    float roundContactSeconds(ResolvedAttack.Shot profile, float delay) {
        return ANTICIPATION_SECONDS + flightSeconds + (profile != null && profile.machineGun() ? delay : 0);
    }

    /** Approach, stationary strike, recovery, then a faster return share this event's one clock. */
    float approachWeight() {
        return seconds <= contactSeconds + RECOVERY_SECONDS ? smooth(seconds / approachSeconds)
              : 1 - smooth((seconds - contactSeconds - RECOVERY_SECONDS) / MELEE_RUN_SECONDS);
    }

    float approachFacingWeight() {
        return returning() ? approachWeight() : smooth(seconds / (approachSeconds * .35f));
    }

    float dodgeWeight() {
        if (shot() || death() || removal() || event.result().hit() || event.target() == null) { return 0; }
        return seconds <= contactSeconds ? smooth((seconds - approachSeconds) / MELEE_SWING_SECONDS)
              : 1 - smooth((seconds - contactSeconds) / RECOVERY_SECONDS);
    }

    Vector3 dodgeOffset(Vector3 result) {
        return result.set(BoardGeometry.center(event.destination().coords(), 0))
              .sub(BoardGeometry.center(event.attacker().location().coords(), 0)).nor()
              .scl(BoardGeometry.height() * MELEE_DODGE_HEXES * dodgeWeight());
    }

    float travelProgress() {
        return seconds < approachSeconds ? seconds / approachSeconds
              : seconds > contactSeconds + RECOVERY_SECONDS ? (seconds - contactSeconds - RECOVERY_SECONDS) / MELEE_RUN_SECONDS : 0;
    }

    boolean returning() { return seconds > contactSeconds + RECOVERY_SECONDS; }

    float contactWeight() {
        return seconds <= contactSeconds ? smooth((seconds - contactSeconds + .16f) / .16f)
              : 1 - smooth((seconds - contactSeconds) / RECOVERY_SECONDS);
    }

    boolean thrust() {
        String weapon = event.result().equipmentName().toLowerCase(java.util.Locale.ROOT);
        return weapon.contains("lance") || weapon.contains("spear");
    }

    float strike() {
        if (shot() || death()) { return 0; }
        if (seconds < contactSeconds) {
            return smooth((seconds - approachSeconds) / MELEE_SWING_SECONDS);
        }
        return 1 - smooth((seconds - contactSeconds) / RECOVERY_SECONDS);
    }

    float windup() {
        float start = event.result().kind() == ResolvedAttack.Kind.PUNCH || event.result().kind() == ResolvedAttack.Kind.KICK
              ? approachSeconds : approachSeconds * .45f;
        return smooth((seconds - start) / .25f) * (1 - smooth((seconds - contactSeconds) / RECOVERY_SECONDS));
    }

    static float smooth(float value) {
        float t = MathUtils.clamp(value, 0, 1);
        return t * t * (3 - 2 * t);
    }
}
