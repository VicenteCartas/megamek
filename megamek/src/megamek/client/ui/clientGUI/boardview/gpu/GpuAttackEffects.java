/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.Disposable;
import megamek.common.ResolvedAttack;
import megamek.common.board.Coords;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.WeaponType;
import megamek.common.weapons.handlers.AreaEffectHelper;
import megamek.logging.MMLogger;

/** Bounded procedural effects using the posed assembly's actual muzzle/launcher emitters. No rules or hit tests. */
final class GpuAttackEffects implements Disposable {
    private static final MMLogger LOGGER = MMLogger.create(GpuAttackEffects.class);
    private static final int MAX_MUZZLES = 512;
    private static final int FLAME_PUFFS = 32;
    private static final float MUZZLE_BLAST_SECONDS = .11f;
    private static final float MUZZLE_SMOKE_SECONDS = .34f;
    /** A Gauss round's electromagnetic discharge at the muzzle; it burns nothing, so it leaves no fire or smoke. */
    private static final float GAUSS_DISCHARGE_SECONDS = .18f;
    private static final float PPC_BEAM_WIDTH = 4.3f;
    // Beam shader kinds; particle kinds belong to the separate particle shader.
    private static final int LASER = 0, PPC = 1, PPC_GLOW = 2;
    /** A TAG that misses only flickers, faintly and briefly, off past its target. */
    private static final float DESIGNATOR_MISS_SECONDS = .3f;
    // projectiles.frag kinds. Fractional parts carry animation phase from the existing attack clock.
    static final int TRACER = 0, ENERGY_TRAIL = 1, ENERGY_GLOW = 2, FLARE_GLOW = 3, SPRAY = 4, SCREEN = 5, CONTACT_GLOW = 6;
    static final int IMPACT_SPARK = 7;
    private final Vector3[] origins = new Vector3[MAX_MUZZLES];
    private final Vector3[] muzzles = new Vector3[MAX_MUZZLES];
    private final Vector3[] forwards = new Vector3[MAX_MUZZLES];
    private final Vector3[] endpoints = new Vector3[MAX_MUZZLES];
    private final String[] effects = new String[MAX_MUZZLES];
    private final String[] emissionKeys = new String[MAX_MUZZLES];
    private final boolean[] arcs = new boolean[MAX_MUZZLES];
    private final boolean[] impacts = new boolean[MAX_MUZZLES];
    private final float[] flameSizes = new float[MAX_MUZZLES];
    private final float[] roundDelays = new float[MAX_MUZZLES];
    private final Trace[] emitted = new Trace[MAX_MUZZLES];
    private final ResolvedAttack.Shot[] profiles = new ResolvedAttack.Shot[MAX_MUZZLES];
    private final Vector3 target = new Vector3();
    private final Vector3 direction = new Vector3();
    private final Vector3 point = new Vector3();
    private final Vector3 end = new Vector3();
    private UnitAttack attack;
    private List<UnitAttack> volley = List.of();
    private GpuUnitModels library;
    private Map<String, ModelInstance> instances = Map.of();
    private final GpuMissileEffects missiles = new GpuMissileEffects();
    private final GpuExplosionEffects explosions = new GpuExplosionEffects();
    /** The brightest effect lights a frame hands the terrain: binning stays cheap under the heaviest barrage. */
    static final int MAX_LIGHTS = 64;
    private static final Vector3 MUZZLE_FIRE = new Vector3(2.4f, 1.4f, .6f);
    private static final Vector3 PPC_LIGHT = new Vector3(.6f, 1.1f, 2.6f);
    private static final Vector3 FLAMER_LIGHT = new Vector3(2.4f, 1f, .3f);
    /**
     * Laser colours by weapon (the BattleTech convention): IS small red, medium green, large blue, their ER versions
     * with a white-hot core; Clan ER small orange, medium green, large violet, white cores; Clan heavy small yellow,
     * medium cyan, large magenta. Alpha is the core's whiteness.
     */
    private static final Color[][] LASERS = {
        { new Color(.9f, .08f, .06f, .2f), new Color(.15f, .8f, .15f, .2f), new Color(.15f, .2f, .9f, .2f) },
        { new Color(.9f, .1f, .08f, .85f), new Color(.15f, .8f, .2f, .85f), new Color(.3f, .3f, .95f, .85f) },
        { new Color(1, .4f, .1f, .8f), new Color(.25f, .85f, .35f, .85f), new Color(.5f, .3f, 1, .85f) },
        { new Color(1, .85f, .15f, .4f), new Color(.1f, .85f, .8f, .4f), new Color(.85f, .2f, .85f, .4f) } };
    private Color laser = LASERS[0][0];
    /** Whether the current attack's weapon is a Gauss rifle: magnetic coils, not propellant, throw its slugs. */
    private boolean gauss;
    /** The current laser's light: its colour in linear light, as bright as a muzzle flash. */
    private Vector3 laserLight = new Vector3();
    /** The current laser's or PPC's light power against its family's reference: {@link #beamScale(WeaponType, int)}. */
    private float beamScale = 1;
    /**
     * The power of a medium laser's light along its beam and of its glow where it strikes, both sized .35 hex. The
     * light along a beam is spread over its length (lava-lighting.glsl).
     */
    static final float BEAM_LIGHT = .45f, STRIKE_LIGHT = .5f;
    /**
     * A standard PPC's, far brighter: a band along its bolt, a broad glow on its target, and round the weapon as it
     * charges.
     */
    static final float PPC_BEAM_LIGHT = 12, PPC_STRIKE_LIGHT = 1.5f, PPC_CHARGE_LIGHT = 8;
    /** This frame's weapon lights: muzzle blasts, laser and PPC light, flamer streams. */
    private final List<GpuLavaLighting.Source> weaponLights = new ArrayList<>();
    private final GpuArtilleryBlast artilleryBlasts = new GpuArtilleryBlast();
    private Map<Coords, Integer> minefields = Map.of();
    private Function<Ray, BoardGeometry.Hit> minedLandscape = ray -> null;
    private final GpuEffectBatch flames = new GpuEffectBatch(2048);
    private final GpuEffectBatch beams = new GpuEffectBatch(MAX_MUZZLES * 4, "beams");
    private final GpuEffectBatch projectiles = new GpuEffectBatch(512, "projectiles");
    private final Vector3 electricAxis = new Vector3(), electricSide = new Vector3(), electricUp = new Vector3();
    private final Vector3 electricStart = new Vector3(), electricEnd = new Vector3();
    private final Vector3 flameWidth = new Vector3(), flameLength = new Vector3();
    private final Vector3 flameSide = new Vector3(), flameUp = new Vector3();
    private final Vector3 sparkAxis = new Vector3(), sparkSide = new Vector3(), sparkUp = new Vector3(), sparkVelocity = new Vector3();
    private Camera camera;
    /**
     * A round's flight.
     *
     * @param opening whether the round opened in the air at {@code target}, short of its {@code aim}: a cluster burst or
     *                a minefield dispenser
     */
    record Trace(Vector3 origin, Vector3 forward, Vector3 target, boolean impact, Vector3 aim, float progress,
          boolean opening, boolean infantryFigure) {
        Trace(Vector3 origin, Vector3 forward, Vector3 target, boolean impact) { this(origin, forward, target, impact, target, 1); }
        Trace(Vector3 origin, Vector3 forward, Vector3 target, boolean impact, Vector3 aim, float progress) {
            this(origin, forward, target, impact, aim, progress, false, false);
        }
        Vector3 position(float at, boolean arc, Vector3 result) {
            return at >= 1 ? result.set(target) : UnitAttack.projectile(origin, aim, at * progress, arc, result);
        }
    }
    private final Map<UnitAttack, Map<String, Trace>> traces = new HashMap<>();
    private final Map<UnitAttack, Map<String, GpuMissileEffects.Launch>> launches = new HashMap<>();
    private final java.util.Set<UnitAttack> groundComplete = new java.util.HashSet<>();
    private int muzzleCount;
    private final java.util.List<Trace> interceptionBeams = new java.util.ArrayList<>();
    private int airbursts;

    List<Trace> interceptionBeams() { return List.copyOf(interceptionBeams); }
    int airburstCount() { return airbursts; }

    void update(UnitAttack next, GpuUnitModels library, Map<String, ModelInstance> instances) {
        update(next == null ? List.of() : List.of(next), library, instances);
    }

    void update(List<UnitAttack> next, GpuUnitModels library, Map<String, ModelInstance> instances) {
        update(next, List.of(), library, instances);
    }

    void update(List<UnitAttack> next, List<UnitAttack> completed, GpuUnitModels library, Map<String, ModelInstance> instances) {
        this.volley = next;
        this.library = library;
        this.instances = instances;
        var retained = new java.util.HashSet<>(next);
        retained.addAll(completed);
        launches.keySet().retainAll(retained);
        traces.keySet().retainAll(retained);
        groundComplete.retainAll(retained);
        missiles.begin();
        explosions.begin();
    }

    private void prepare(UnitAttack next) {
        attack = next;
        muzzleCount = 0;
        var weapon = EquipmentType.get(attack.event.result().equipmentName()) instanceof WeaponType type ? type : null;
        laser = laserTint(weapon, attack.event.attackerClan());
        gauss = weapon != null && weapon.hasFlag(WeaponType.F_GAUSS);
        laserLight = laserLight(laser);
        beamScale = beamScale(weapon,
              attack.event.attacker().location().coords().distance(attack.event.destination().coords()));
        if (attack.event.attacker().sensorContact()) { return; }
        var event = attack.event;
        var unit = event.attacker();
        var instance = instances.get(unit.id() + ":" + unit.part());
        var assembly = library == null ? null : library.loaded(unit.model(), unit.id(), unit.part());
        var receiving = event.target();
        var victim = receiving == null ? null : instances.get(receiving.id() + ":" + receiving.part());
        UnitAttack.center(instance, unit.location(), point);
        attack.endpoint(victim, point, target);
        var captured = launches.computeIfAbsent(attack, ignored -> new HashMap<>());
        var paths = traces.computeIfAbsent(attack, ignored -> new HashMap<>());
        captured.values().forEach(missiles::add);
        var represented = new java.util.HashSet<String>();
        if (assembly != null && instance != null) {
            for (var binding : assembly.equipment()) {
                int owner = binding.memberId() < 0 ? event.entityId() : binding.memberId();
                represented.add(owner + ":" + binding.index());
                if (captured.containsKey(binding.node())) { continue; }
                var attachment = instance.getNode(binding.node());
                if (!attack.fires(binding) || attachment == null || !UnitBounds.subtree(attachment).isValid()) { continue; }
                var emitters = binding.emitters().stream().filter(GpuAttackEffects::firingEmitter)
                      .filter(emitter -> instance.getNode(emitter.node()) != null).toList();
                if (emitters.isEmpty()) { continue; }
                var profile = attack.profile(binding);
                int count = missileCount(profile, emitters.getFirst().effect());
                if (count > 0) {
                    if (attack.seconds < UnitAttack.ANTICIPATION_SECONDS) { continue; }
                    var ports = new Vector3[emitters.size()];
                    for (int index = 0; index < ports.length; index++) {
                        ports[index] = new Vector3();
                        UnitModelAttachment.emitter(instance, emitters.get(index), ports[index], direction);
                    }
                    launch(captured, binding.node(), owner, binding.index(), ports, victim, count, profile);
                    continue;
                }
                int rounds = UnitAttack.roundCount(profile);
                boolean bullet = "bullet".equals(emitters.getFirst().effect());
                // A physical round belongs to one barrel; its ordinal is never restarted for each barrel.
                for (int index = 0; index < (bullet ? rounds : emitters.size()) && muzzleCount < MAX_MUZZLES; index++) {
                    int barrel = bullet ? index % emitters.size() : index;
                    var emitter = emitters.get(barrel);
                    liveMuzzle();
                    UnitModelAttachment.emitter(instance, emitter, muzzles[muzzleCount], forwards[muzzleCount]);
                    float size = MathUtils.clamp(UnitBounds.subtree(attachment).getDimensions(point).len()
                          * instance.transform.getScaleX() * .5f, 1.5f, 14);
                    String key = binding.node() + ":" + (barrel + 1) + (bullet && index > 0 ? ":round-" + index : "");
                    // A TAG is a designator whatever its model's emitter says.
                    String effect = attack.firesTag(owner, binding.index()) ? "designator" : emitter.effect();
                    emission(paths, key, effect, profile, victim, size, bullet ? attack.roundDelay(index, profile) : 0);
                }
            }
        }
        // No mesh is required for firing. Preserve each observed physical gun, even in a mixed weapon bay.
        for (var mount : event.result().mounts()) {
            int owner = mount.entityId(), index = mount.equipmentIndex();
            String key = owner + ":" + index;
            if (represented.contains(key) || !attack.fires(owner, index) || captured.containsKey(key)) { continue; }
            var profile = attack.profile(owner, index);
            String effect = attack.effect(owner, index);
            if ("none".equals(effect)) { continue; }
            int count = missileCount(profile, effect);
            if (count > 0) {
                if (attack.seconds >= UnitAttack.ANTICIPATION_SECONDS) {
                    launch(captured, key, owner, index,
                          new Vector3[] { UnitAttack.center(instance, unit.location(), new Vector3()) }, victim, count, profile);
                }
                continue;
            }
            int rounds = "bullet".equals(effect) ? UnitAttack.roundCount(profile) : 1;
            for (int round = 0; round < rounds && muzzleCount < MAX_MUZZLES; round++) {
                liveMuzzle();
                UnitAttack.center(instance, unit.location(), muzzles[muzzleCount]);
                forwards[muzzleCount].set(target).sub(muzzles[muzzleCount]).nor();
                emission(paths, key + ":round-" + round, effect, profile, victim, 2, attack.roundDelay(round, profile));
            }
        }
    }

    private static int missileCount(ResolvedAttack.Shot profile, String effect) {
        return profile != null && profile.missiles() > 0 ? profile.missiles()
              : "missile".equals(effect) || "cluster".equals(effect) ? 1 : 0;
    }

    private void launch(Map<String, GpuMissileEffects.Launch> captured, String key, int owner, int index,
          Vector3[] ports, ModelInstance victim, int count, ResolvedAttack.Shot profile) {
        var launch = GpuMissileEffects.capture(attack, ports, victim, count, attack.missileHits(owner, index, count),
              profile != null && (profile.indirect() || profile.artillery()), key.hashCode() ^ attack.event.result().id().hashCode(), profile,
              attack.interceptedMissiles(owner, index, count));
        captured.put(key, launch);
        missiles.add(launch);
    }

    private void liveMuzzle() {
        if (muzzles[muzzleCount] == null) {
            muzzles[muzzleCount] = new Vector3();
            forwards[muzzleCount] = new Vector3();
        }
    }

    /** Capture each emitted round once. Beams follow the live nozzle; projectiles and released smoke do not. */
    private void emission(Map<String, Trace> paths, String key, String effect, ResolvedAttack.Shot profile,
          ModelInstance victim, float size, float delay) {
        Trace trace = paths.get(key);
        if (trace == null && attack.seconds >= UnitAttack.ANTICIPATION_SECONDS + delay) {
            var start = muzzles[muzzleCount].cpy();
            var finish = new Vector3();
            boolean impact;
            boolean isBeam = "laser".equals(effect) || "ppc".equals(effect) || "designator".equals(effect);
            if (isBeam) { impact = attack.beamEndpoint(victim, start, key.hashCode(), finish); }
            else { attack.endpoint(victim, start, !attack.event.result().hit(), key.hashCode(), finish); impact = true; }
            boolean missed = !attack.event.result().hit() && !attack.defensive();
            boolean arc = profile != null && (profile.indirect() || profile.artillery());
            var aim = finish.cpy();
            var contact = missed && !isBeam
                  ? attack.landscapeContact(t -> UnitAttack.projectile(start, aim, (float) t, arc, new Vector3()), arc ? 32 : 1) : null;
            trace = new Trace(start, forwards[muzzleCount].cpy(), contact == null ? finish : contact.point(), impact,
                  aim, contact == null ? 1 : contact.progress(), false, attack.hitsInfantryFigure(victim, key.hashCode()));
            trace = openInTheAir(trace, profile, arc);
            paths.put(key, trace);
        }
        origin(muzzleCount).set(trace == null ? muzzles[muzzleCount] : trace.origin());
        endpoint(muzzleCount).set(trace == null ? target : trace.target());
        emitted[muzzleCount] = trace;
        impacts[muzzleCount] = trace != null && trace.impact();
        flameSizes[muzzleCount] = size;
        arcs[muzzleCount] = profile != null && (profile.indirect() || profile.artillery());
        roundDelays[muzzleCount] = delay;
        profiles[muzzleCount] = profile;
        emissionKeys[muzzleCount] = key;
        effects[muzzleCount++] = effect;
    }

    /**
     * Ends a cluster or minefield shell's flight where it comes down to its opening height, so its burst or dispenser
     * opens where the shell is. A shell that strikes the landscape before then, or never climbs that high, keeps its
     * flight.
     */
    private Trace openInTheAir(Trace trace, ResolvedAttack.Shot profile, boolean arc) {
        float height = GpuArtilleryBlast.openingHeight(attack.event.result().equipmentName(), profile);
        if (height <= 0) {
            return trace;
        }
        float progress = GpuArtilleryBlast.openingProgress(
              t -> UnitAttack.projectile(trace.origin(), trace.aim(), (float) t, arc, new Vector3()), trace.aim(), height);
        if ((progress < 0) || (progress >= trace.progress())) {
            LOGGER.debug("[ArtilleryBlast] shell opens over its target: it never comes down from its opening height"
                  + " or strikes the landscape first");
            return trace;
        }
        Vector3 opening = UnitAttack.projectile(trace.origin(), trace.aim(), progress, arc, new Vector3());
        LOGGER.debug("[ArtilleryBlast] shell opens in the air {} of the way along its flight, at {}", progress, opening);
        return new Trace(trace.origin(), trace.forward(), opening, trace.impact(), trace.aim(), progress, true, trace.infantryFigure());
    }

    private static boolean firingEmitter(UnitModelDescriptor.Emitter emitter) {
        return "muzzle".equals(emitter.role()) || "beam".equals(emitter.role()) || "launcher".equals(emitter.role())
              || "contact".equals(emitter.role());
    }

    private Vector3 endpoint(int index) {
        if (endpoints[index] == null) { endpoints[index] = new Vector3(); }
        return endpoints[index];
    }

    int missileCount() { return missiles.missileCount(); }
    int smokeCount() { return missiles.smokeCount(); }
    List<Trace> emissions(UnitAttack shot) { return List.copyOf(traces.getOrDefault(shot, Map.of()).values()); }
    List<GpuMissileEffects.Launch> missileLaunches(UnitAttack shot) { return List.copyOf(launches.getOrDefault(shot, Map.of()).values()); }

    /** Reuse the captured projectile/beam endpoints, including missed members of a partially hitting salvo. */
    void groundImpacts(List<UnitAttack> completed, java.util.function.Consumer<GpuGroundDamage.Impact> receiver) {
        if (!GpuGroundDamage.COMBAT_SCARS) { return; }
        for (var list : List.of(volley, completed)) {
            for (var shot : list) {
                if (!shot.shot() || shot.defensive() || shot.event.attacker().sensorContact()) { continue; }
                if (groundComplete.contains(shot) || shot.seconds < UnitAttack.ANTICIPATION_SECONDS) { continue; }
                prepare(shot);
                for (int index = 0; index < muzzleCount; index++) {
                    var profile = profiles[index];
                    boolean unitHit = shot.event.result().hit() && shot.event.target() != null
                          && (profile == null || profile.impact() == null);
                    if (unitHit || !impacts[index] || shot.seconds < contactSeconds(index)) { continue; }
                    String effect = effects[index];
                    if (!List.of("bullet", "laser", "ppc", "energy", "missile", "cluster", "bomb", "flame").contains(effect)) { continue; }
                    // A cluster round leaves no crater: its bomblets freckle the ground (GpuArtilleryBlast.groundMarks).
                    if (GpuArtilleryBlast.leavesNoCrater(shot.event.result().equipmentName(), profile)) { continue; }
                    receiver.accept(new GpuGroundDamage.Impact(shot, emissionKeys[index], origins[index], endpoints[index], effect, profile));
                }
                for (var entry : launches.getOrDefault(shot, Map.of()).entrySet()) {
                    var launch = entry.getValue();
                    if (GpuArtilleryBlast.leavesNoCrater(shot.event.result().equipmentName(), launch.profile())) { continue; }
                    // Sample actual ground arrivals, so a single miss in a mostly hitting rack cannot be skipped.
                    var arrivals = new java.util.ArrayList<Integer>();
                    for (int index = 0; index < launch.missiles(); index++) {
                        boolean unitHit = launch.hit(index) && shot.event.target() != null
                              && (launch.profile() == null || launch.profile().impact() == null);
                        if (!unitHit && !launch.intercepted(index) && shot.seconds >= launch.endSeconds(index)) { arrivals.add(index); }
                    }
                    int step = Math.max(1, (arrivals.size() + 11) / 12);
                    for (int ordinal = 0; ordinal < arrivals.size(); ordinal += step) {
                        int index = arrivals.get(ordinal);
                        receiver.accept(new GpuGroundDamage.Impact(shot, entry.getKey() + ":missile-" + index,
                              launch.origins()[index % launch.origins().length], launch.targets()[index], "missile", launch.profile()));
                    }
                }
                if (shot.seconds >= shot.contactSeconds) { groundComplete.add(shot); }
            }
        }
    }
    int flameParticleCount() { return flames.size(); }

    private float contactSeconds(int index) {
        float start = UnitAttack.ANTICIPATION_SECONDS + roundDelays[index];
        return MathUtils.lerp(start, attack.roundContactSeconds(profiles[index], roundDelays[index]),
              emitted[index] == null ? 1 : emitted[index].progress());
    }
    int explosionCount() { return explosions.size(); }
    int artilleryBlastCount() { return artilleryBlasts.size(); }

    /** Ages the artillery blasts, which outlive the attacks that landed them, by the frame's real time. */
    void advance(float seconds) { artilleryBlasts.advance(seconds); }

    /** The live nuclear blasts' shock fronts (GpuArtilleryBlast#shocks); how many. */
    int shocks(float[] out) { return artilleryBlasts.shocks(out); }

    /** A different board ends persistent blasts; their reusable GPU effects and warm programs stay owned here. */
    void clearArtilleryBlasts() { artilleryBlasts.clear(); }

    /**
     * The density of each minefield the local player knows of, by hex, whose mines lie on the ground until it is
     * gone, and the terrain they lie on. An empty map draws none.
     */
    void setMinefields(Map<Coords, Integer> minefields, Function<Ray, BoardGeometry.Hit> landscape) {
        this.minefields = Map.copyOf(minefields);
        minedLandscape = landscape;
    }

    /** Where each illumination round that just landed burst, by its target hex. */
    Map<Coords, Vector3> starShellOpenings() { return artilleryBlasts.starShellOpenings(); }

    /** Scorch marks where artillery debris has just landed in the rings around its target hex. */
    /**
     * The last drawn frame's fireballs and weapon lights as local lights, the brightest {@link #MAX_LIGHTS}: the
     * terrain draws before the effects, so its light trails the effects by one frame.
     */
    List<GpuLavaLighting.Source> lights() {
        List<GpuLavaLighting.Source> lights = new ArrayList<>(weaponLights);
        explosions.lights(lights);
        if (lights.size() > MAX_LIGHTS) {
            lights.sort(Comparator.comparingDouble((GpuLavaLighting.Source light) -> light.power() * light.size()
                  * light.size()).reversed());
            lights = lights.subList(0, MAX_LIGHTS);
        }
        return List.copyOf(lights);
    }

    void artilleryGroundMarks(Consumer<GpuGroundDamage.Impact> receiver) {
        artilleryBlasts.groundMarks(receiver);
    }

    /** The brightest nuclear flash now, for the whole view. */
    GpuArtilleryBlast.Flash nuclearFlash() { return artilleryBlasts.flash(); }

    /** A packet-confirmed scheduled strike has no unit flight, but shares normal nuclear flash, blast and scorch. */
    void detonate(megamek.common.NuclearDetonation event) {
        artilleryBlasts.detonateNuclear(BoardGeometry.center(event.coords(), event.elevation()), event.stats(),
              event.id().hashCode(), event.kind());
    }

    /** Each nuclear blast's scorch over its blast area, once, as it goes off. */
    void artilleryScorches(Consumer<GpuGroundDamage.Scorch> receiver) {
        artilleryBlasts.scorches(receiver);
    }

    /** Each nuclear round's blast, once, as it goes off: its attack's result and the hex it landed in. */
    void artilleryStrikes(java.util.function.BiConsumer<megamek.common.ResolvedAttack, Coords> receiver) {
        artilleryBlasts.strikes(receiver);
    }

    void setSmokeLight(Color light) {
        flames.setSmokeLight(light);
        missiles.setSmokeLight(light);
        explosions.setSmokeLight(light);
    }

    void setLightDirection(Vector3 direction) { explosions.setLightDirection(direction); }
    void setWind(BoardAtmosphere.Effects weather) { explosions.setWind(weather); }

    /** How fuel-air rounds burn in the board's atmosphere, which their blast follows as the rules do. */
    void setFuelAirStrength(AreaEffectHelper.FuelAirStrength strength) { artilleryBlasts.setFuelAirStrength(strength); }

    float flameSize() {
        float size = 0;
        for (int index = 0; index < muzzleCount; index++) {
            if ("flame".equals(effects[index])) { size = Math.max(size, flameSizes[index]); }
        }
        return size;
    }

    private Vector3 origin(int index) {
        if (origins[index] == null) {
            origins[index] = new Vector3();
        }
        return origins[index];
    }

    void render(Camera camera) {
        render(camera, null);
    }

    void render(Camera camera, GpuEffectDepth depth) {
        render(camera, depth, List.of());
    }

    void render(Camera camera, GpuEffectDepth depth, List<MeepleCasualties.Casualty> casualties) {
        render(camera, depth, casualties, List.of());
    }

    /** With the dust of the structures coming down ({@code collapses}) beside the casualties' explosions. */
    void render(Camera camera, GpuEffectDepth depth, List<MeepleCasualties.Casualty> casualties,
          List<java.util.function.Consumer<GpuExplosionEffects>> collapses) {
        interceptionBeams.clear();
        airbursts = 0;
        explosions.begin();
        weaponLights.clear();
        missiles.begin();
        flames.begin();
        beams.begin();
        projectiles.begin();
        if (volley.isEmpty() && artilleryBlasts.size() == 0 && minefields.isEmpty() && casualties.isEmpty()
              && collapses.isEmpty()) { return; }
        this.camera = camera;
        casualties.forEach(casualty -> casualty.explosions(explosions));
        collapses.forEach(collapse -> collapse.accept(explosions));
        for (var shot : volley) {
            if (shot.defensive() && shot.interception() != null) { continue; }
            if (shot.seconds < (shot.death() ? 0 : UnitAttack.ANTICIPATION_SECONDS) && !shot.chargingPpc()
                  || shot.seconds >= shot.duration) { continue; }
            prepare(shot);
            boolean infantryMeeples = shot.death() && casualties.stream()
                  .anyMatch(casualty -> casualty.unit.id() == shot.event.entityId() && casualty.infantryMeeples());
            renderShot(infantryMeeples);
        }
        renderInterceptions();
        artilleryBlasts.emit(explosions);
        artilleryBlasts.restingMines(explosions, minefields, minedLandscape);
        missiles.render(camera);
        projectiles.render(camera, 0);
        if (depth == null) { explosions.render(camera); }
        else { explosions.render(camera, depth); }
        // Alpha blending retains orange/red detail where flame puffs overlap instead of bleaching into a beam.
        flames.render(camera, flames.size());
        beams.render(camera, 0);
    }

    private record CounterMuzzle(Vector3 origin, String effect) { }

    /** Counter-fire samples the very same path and clock as the incoming missile and its disappearing trail. */
    private void renderInterceptions() {
        // Counter-fire deals no damage of its own: its lasers keep the reference light, not the last shot's.
        beamScale = 1;
        for (var salvo : volley) {
            if (salvo.defensive() || salvo.interception() == null) { continue; }
            var captured = launches.get(salvo);
            if (captured == null) { continue; }
            var counters = new java.util.ArrayList<CounterMuzzle>();
            for (var counter : volley) {
                if (!counter.defensive() || counter.interception() == null
                      || !counter.interception().id().equals(salvo.interception().id())) { continue; }
                var unit = counter.event.attacker();
                var instance = instances.get(unit.id() + ":" + unit.part());
                var assembly = library == null ? null : library.loaded(unit.model(), unit.id(), unit.part());
                int before = counters.size();
                if (assembly != null && instance != null) {
                    for (var binding : assembly.equipment()) {
                        if (!counter.fires(binding)) { continue; }
                        for (var emitter : binding.emitters()) {
                            if (!firingEmitter(emitter) || instance.getNode(emitter.node()) == null) { continue; }
                            var muzzle = new Vector3();
                            UnitModelAttachment.emitter(instance, emitter, muzzle, direction);
                            counters.add(new CounterMuzzle(muzzle, emitter.effect()));
                        }
                    }
                }
                if (counters.size() == before) {
                    counters.add(new CounterMuzzle(UnitAttack.center(instance, unit.location(), new Vector3()),
                          counter.effect(unit.id(), counter.event.result().equipmentIndex())));
                }
            }
            int ordinal = 0;
            for (var launch : captured.values()) {
                for (int missile = 0; missile < launch.missiles(); missile++) {
                    boolean destroyed = launch.intercepted(missile);
                    // A defense which fired but stopped nothing still shoots at an incoming round.
                    if (!destroyed && (launch.intercepted() > 0 || missile != 0)) { continue; }
                    float stop = destroyed ? launch.endProgress(missile)
                          : UnitAttack.interceptProgress(launch.origins()[0], launch.targets()[missile]);
                    float start = UnitAttack.ANTICIPATION_SECONDS;
                    float contact = destroyed ? launch.endSeconds(missile)
                          : MathUtils.lerp(start, salvo.roundContactSeconds(launch.profile(), 0), stop);
                    float age = salvo.seconds - contact;
                    var intercept = GpuMissileEffects.position(launch, missile, stop, new Vector3());
                    if (destroyed && age >= 0 && age < UnitAttack.RECOVERY_SECONDS) {
                        float fade = 1 - age / UnitAttack.RECOVERY_SECONDS;
                        explosions.burst(intercept, 7, 1 - fade, launch.seed() + missile);
                        airbursts++;
                    }
                    if (counters.isEmpty()) { continue; }
                    var counter = counters.get(ordinal++ % counters.size());
                    if ("laser".equals(counter.effect())) {
                        if (age < -.04f || age > .06f) { continue; }
                        GpuMissileEffects.position(launch, missile,
                              Math.min(stop, GpuMissileEffects.progress(launch, missile, salvo.seconds)), end);
                        beam(counter.origin(), end, .5f, LASER, 1 - Math.max(0, age) / .06f);
                        interceptionBeams.add(new Trace(counter.origin().cpy(), new Vector3(), end.cpy(), destroyed));
                    } else {
                        if (destroyed && age >= 0 && age < UnitAttack.RECOVERY_SECONDS) {
                            impactSparks(counter.origin(), intercept, age / UnitAttack.RECOVERY_SECONDS, .7f, 8, launch.seed() + missile, true);
                        }
                        for (int round = 0; round < 3; round++) {
                            float t = (age + .12f - round * .02f) / (.12f - round * .02f);
                            if (t < 0 || t >= 1) { continue; }
                            point.set(counter.origin()).lerp(intercept, t);
                            end.set(counter.origin()).lerp(intercept, Math.max(0, t - .16f));
                            projectiles.ribbon(camera, end, point, .225f, TRACER, 1);
                        }
                    }
                }
            }
        }
    }

    private void renderShot(boolean infantryMeeples) {
        if (attack.death()) {
            if (!attack.event.result().crewLoss() && !infantryMeeples) {
                explosions.burst(target, 18, attack.deathProgress(), attack.event.result().id().hashCode());
            }
            return;
        }
        if (attack.shot()) {
            for (int index = 0; index < muzzleCount; index++) {
                String effect = effects[index];
                Vector3 start = origins[index];
                Vector3 target = endpoints[index];
                if ("ppc".equals(effect)) {
                    ppc(muzzles[index], target, index);
                    float flash = attack.contactFlash(attack.roundContactSeconds(profiles[index], 0));
                    if (impacts[index] && flash > 0) { electricGlow(target, 3 + (1 - flash) * 5, flash); }
                    continue;
                }
                if ("designator".equals(effect)) {
                    designator(muzzles[index], target, attack.event.result().hit());
                    continue;
                }
                if (attack.seconds < UnitAttack.ANTICIPATION_SECONDS) { continue; }
                if ("flame".equals(effect)) {
                    flame(start, target, flameSizes[index]);
                    continue;
                }
                float cannon = UnitAttack.cannonScale(profiles[index]);
                float age = attack.seconds - UnitAttack.ANTICIPATION_SECONDS - roundDelays[index];
                if (age < 0 || emitted[index] == null) { continue; }
                if (gauss && "bullet".equals(effect)) {
                    gaussDischarge(muzzles[index], forwards[index], gaussSize(profiles[index]), age);
                } else if (cannon > 0 && "bullet".equals(effect)) {
                    cannonMuzzle(muzzles[index], forwards[index], emitted[index], cannon, age, index * 31);
                }
                float contact = contactSeconds(index);
                float flash = attack.contactFlash(contact);
                if (impacts[index] && flash > 0) {
                    // Tube artillery is ballistic like an autocannon, but its shell explodes rather than sparking.
                    boolean isShell = GpuArtilleryBlast.drawsLanding(profiles[index]);
                    if ("bullet".equals(effect) && !isShell) {
                        boolean targetHit = attack.event.result().hit() && attack.event.target() != null
                              && (profiles[index] == null || profiles[index].impact() == null);
                        ballisticImpact(start, target, flash, profiles[index], attack.event.result().id().hashCode() ^ index * 31, targetHit);
                    } else if ("energy".equals(effect)) { glow(target, 1.5f + (1 - flash) * 3, ENERGY_GLOW, 1 - flash, flash); }
                    else if (!"laser".equals(effect) || !emitted[index].infantryFigure()) {
                        // A soldier carbonizes under a laser without a fireball; armor and vehicles keep their impact flash.
                        Trace flight = emitted[index];
                        boolean opened = (flight != null) && flight.opening();
                        impact(target, opened ? flight.aim() : target, flash, profiles[index]);
                    }
                }
                if (attack.seconds >= contact) { continue; }
                float t = MathUtils.clamp(age / Math.max(.0001f, contact - UnitAttack.ANTICIPATION_SECONDS - roundDelays[index]), 0, 1);
                switch (effect) {
                    case "laser" -> beam(muzzles[index], target, .65f, LASER, Math.min(1, t * 8));
                    case "energy" -> {
                        point.set(start).lerp(target, t);
                        glow(point, 2.5f, ENERGY_GLOW, t, 1);
                        end.set(start).lerp(target, Math.max(0, t - .13f));
                        projectiles.ribbon(camera, end, point, .7f, ENERGY_TRAIL, .8f);
                        float muzzleFlash = Math.max(0, 1 - (attack.seconds - UnitAttack.ANTICIPATION_SECONDS) / .12f);
                        if (muzzleFlash > 0) { glow(muzzles[index], 2 * muzzleFlash, ENERGY_GLOW, t, muzzleFlash); }
                    }
                    case "spray", "screen" -> {
                        int kind = "screen".equals(effect) ? SCREEN : SPRAY;
                        for (int puff = 0; puff < 6; puff++) {
                            float progress = MathUtils.clamp(t * 1.3f - puff * .06f, 0, 1);
                            point.set(start).lerp(target, progress).add(0, 0, puff * .5f);
                            glow(point, .75f + progress * 1.5f, kind, progress, .65f);
                        }
                    }
                    default -> {
                        float travel = age / (contact - UnitAttack.ANTICIPATION_SECONDS - roundDelays[index]);
                        emitted[index].position(travel, arcs[index], point);
                        emitted[index].position(Math.max(0, travel - .07f), arcs[index], end);
                        // A Gauss slug streaks blue-white; propellant rounds leave the warm tracer.
                        if (gauss) { projectiles.ribbon(camera, end, point, .2f * gaussSize(profiles[index]), ENERGY_TRAIL, 1); }
                        else { projectiles.ribbon(camera, end, point, .275f * Math.max(1, cannon), TRACER, 1); }
                    }
                }
            }
        }
        if (attack.shot()) {
            var launched = launches.get(attack);
            if (launched != null) {
                for (var launch : launched.values()) {
                    // Nearby bursts merge visually; keep their cost bounded independently of rack size.
                    int step = Math.max(1, launch.missiles() / 12);
                    for (int index = 0; index < launch.missiles(); index += step) {
                        if (launch.intercepted(index)) { continue; }
                        boolean opened = GpuMissileEffects.openedInTheAir(launch, index);
                        // A round that opens in the air bursts there; its spent body lands later with no blast.
                        float impact = attack.contactFlash(
                              opened ? launch.openingSeconds(index) : launch.endSeconds(index));
                        if (impact <= 0) { continue; }
                        var profile = launch.profile();
                        boolean isSolidRound = profile != null && profile.ballistic();
                        boolean isShell = GpuArtilleryBlast.drawsLanding(profile);
                        if (isSolidRound && !isShell) {
                            boolean targetHit = launch.hit(index) && attack.event.target() != null && profile.impact() == null;
                            ballisticImpact(launch.origins()[index % launch.origins().length], launch.targets()[index], impact,
                                  profile, launch.seed() ^ index * 31, targetHit);
                        } else if (opened) {
                            impact(GpuMissileEffects.openingPoint(launch, index), launch.aims()[index], impact, profile);
                        } else {
                            impact(launch.targets()[index], launch.targets()[index], impact, profile);
                        }
                    }
                }
            }
        } else if (attack.event.result().hit() && attack.contactFlash() > 0) {
            float impact = attack.contactFlash();
            glow(target, 1 + (1 - impact) * 2, CONTACT_GLOW, 1 - impact, impact);
        }
    }

    /** A short expanding muzzle explosion and lingering exhaust, sharing the existing bounded plume batch. */
    private void cannonMuzzle(Vector3 muzzle, Vector3 forward, Trace emission, float size, float age, int seed) {
        if (age < 0 || age >= MUZZLE_SMOKE_SECONDS) { return; }
        flameSide.set(emission.forward()).crs(Vector3.Z).nor();
        if (flameSide.isZero()) { flameSide.set(Vector3.X); }
        float time = age / MUZZLE_SMOKE_SECONDS;
        for (int puff = 0; puff < 5; puff++) {
            float angle = seed + puff * 2.399963f;
            point.set(emission.origin()).mulAdd(emission.forward(), size * (1 + time * (5 + puff)))
                  .mulAdd(flameSide, MathUtils.sin(angle) * size * time * 2)
                  .add(0, 0, size * time * time * 5);
            muzzlePuff(size * (1 + time * 2), 0, .24f * (1 - time));
        }
        if (age >= MUZZLE_BLAST_SECONDS) { return; }
        flameSide.set(forward).crs(Vector3.Z).nor();
        if (flameSide.isZero()) { flameSide.set(Vector3.X); }
        flameUp.set(flameSide).crs(forward).nor();
        time = age / MUZZLE_BLAST_SECONDS;
        light(muzzle.cpy().mulAdd(forward, size), 1.2f * (1 - time * time), MUZZLE_FIRE, size * 8);
        for (int puff = 0; puff < 7; puff++) {
            float angle = seed + puff * 2.399963f;
            point.set(muzzle).mulAdd(forward, size * (.5f + time * (3 + puff * .6f)))
                  .mulAdd(flameSide, MathUtils.sin(angle) * size * time * 1.5f)
                  .mulAdd(flameUp, MathUtils.cos(angle) * size * time * 1.5f);
            muzzlePuff(size * (1 + time), 2 + puff + time, .9f * (1 - time * time));
        }
    }

    /** A blue-white flash and crackle that leaves the muzzle with the slug and fades: no fire, no smoke. */
    private void gaussDischarge(Vector3 muzzle, Vector3 forward, float size, float age) {
        if (age < 0 || age >= GAUSS_DISCHARGE_SECONDS) { return; }
        float time = age / GAUSS_DISCHARGE_SECONDS, fade = (1 - time) * (1 - time);
        point.set(muzzle).mulAdd(forward, size * .5f);
        electricGlow(point, size * (2 + time * 4), fade);
        point.set(muzzle).mulAdd(forward, size * (1 + time * 6));
        glow(point, size * (1.3f + time * 2), ENERGY_GLOW, time, .9f * fade);
    }

    /** A Gauss discharge grows with its slug's damage: the Light Gauss's 8 to the Heavy Gauss's 25, a HAG's small slugs. */
    private static float gaussSize(ResolvedAttack.Shot profile) {
        return profile == null ? 1.6f : MathUtils.clamp((float) Math.sqrt(Math.max(1, profile.damagePerHit()) / 5), .6f, 2.4f);
    }

    private void muzzlePuff(float radius, float kind, float alpha) {
        flameWidth.set(camera.direction).crs(camera.up).nor().scl(radius);
        flameLength.set(camera.up).scl(radius);
        flames.quad(point, flameWidth, flameLength, -1, kind, alpha);
    }

    /** Muzzle charge, then a continuous hot beam with independently flickering electrical branches. */
    private void ppc(Vector3 muzzle, Vector3 finish, int index) {
        float contact = attack.roundContactSeconds(profiles[index], 0);
        int seed = attack.event.result().id().hashCode() ^ index * 7919 ^ (int) (attack.seconds * 36) * 109;
        if (attack.seconds < UnitAttack.ANTICIPATION_SECONDS) {
            float charge = MathUtils.clamp(attack.seconds / UnitAttack.ANTICIPATION_SECONDS, 0, 1);
            float radius = .4f + charge * 3.5f;
            electricGlow(muzzle, radius, charge);
            light(muzzle.cpy(), PPC_CHARGE_LIGHT * charge * beamScale, PPC_LIGHT, BoardGeometry.width() * .35f);
            for (int arc = 0; arc < 4; arc++) {
                float angle = arc * 2.399963f + charge * 5;
                point.set(muzzle).add(MathUtils.sin(angle) * radius, MathUtils.cos(angle) * radius,
                      (UnitAttack.noise(seed + arc) - .5f) * radius * 2);
                electricArc(muzzle, point, radius * .3f, 3, seed + arc * 31, charge);
            }
        } else if (attack.seconds < contact) {
            float fade = MathUtils.clamp((contact - attack.seconds) / .05f, 0, 1);
            electricGlow(muzzle, 5, fade);
            beam(muzzle, finish, PPC_BEAM_WIDTH, PPC, fade);
            electricArc(muzzle, finish, 5, 8, seed, fade);
            electricArc(muzzle, finish, 9, 8, seed + 137, fade * .8f);
        }
    }

    private void electricGlow(Vector3 center, float radius, float alpha) {
        beams.billboard(camera, center, radius, PPC_GLOW, alpha);
        light(center.cpy(), alpha * beamScale, PPC_LIGHT, radius * 3);
    }

    /**
     * A laser's colour from its weapon: its tech base (a mixed-tech laser takes the firing unit's), its class from its
     * name (ER, heavy; pulse lasers take the standard colour) and its size (small and micro, medium, large). Anything
     * else draws the standard small laser's red.
     */
    static Color laserTint(WeaponType weapon, boolean unitClan) {
        if (weapon == null || !weapon.hasFlag(WeaponType.F_LASER)) { return LASERS[0][0]; }
        boolean clan = weapon.isClan() || (weapon.isMixedTech() && unitClan);
        List<String> words = List.of(weapon.getName().split("[^A-Za-z]+"));
        int size = words.contains("Small") || words.contains("Micro") ? 0 : words.contains("Large") ? 2 : 1;
        int family = words.contains("Heavy") ? 3 : words.contains("ER") ? (clan ? 2 : 1) : 0;
        return LASERS[family][size];
    }

    /**
     * A laser's or PPC's light power against its family's reference, a medium laser (5 damage) or a standard PPC (10):
     * linear in its damage at {@code range} hexes (a variable-damage weapon's for that range bracket), from .4 (a micro
     * laser) to 3 times. Any other weapon, or one without a fixed damage (infantry lasers, bays), keeps the reference.
     */
    static float beamScale(WeaponType weapon, int range) {
        boolean ppc = weapon != null && weapon.hasFlag(WeaponType.F_PPC);
        if (weapon == null || !ppc && !weapon.hasFlag(WeaponType.F_LASER)) { return 1; }
        int damage = weapon.getDamage(range);
        return damage <= 0 ? 1 : MathUtils.clamp(damage / (ppc ? 10f : 5f), .4f, 3);
    }

    /** A laser's light: its tint in linear light, as bright as a muzzle flash. */
    static Vector3 laserLight(Color tint) {
        Vector3 linear = new Vector3((float) Math.pow(tint.r, 2.2), (float) Math.pow(tint.g, 2.2),
              (float) Math.pow(tint.b, 2.2));
        // Every colour lights as brightly as the medium laser's green (Rec. 709 luminance), so its damage alone
        // (beamScale) sets how bright a laser is: a blue large laser no longer lights less than a green medium one.
        return linear.scl(LASER_LUMINANCE / luminance(linear));
    }

    /** The light of the medium laser's green: {@link #laserLight}'s brightness for every laser colour. */
    private static final float LASER_LUMINANCE = 2.6f * luminance(new Vector3((float) Math.pow(.15, 2.2),
          (float) Math.pow(.8, 2.2), (float) Math.pow(.15, 2.2)));

    private static float luminance(Vector3 linear) {
        return Math.max(.2126f * linear.x + .7152f * linear.y + .0722f * linear.z, 1e-4f);
    }

    private void light(Vector3 position, float power, Vector3 color, float size) {
        light(weaponLights, position, null, power, color, size, 0);
    }

    /** Adds a point light, or with an end a segment of that tube radius; the faintest are left out. */
    private static void light(List<GpuLavaLighting.Source> lights, Vector3 position, Vector3 end, float power,
          Vector3 color, float size, float radius) {
        if (power > .02f) { lights.add(new GpuLavaLighting.Source(-1, position, power, color, size, end, radius)); }
    }

    /** Fixed segment count and the shared attack clock keep electrical flicker bounded and deterministic. */
    private void electricArc(Vector3 start, Vector3 finish, float spread, int segments, int seed, float alpha) {
        electricAxis.set(finish).sub(start);
        electricSide.set(electricAxis).crs(Vector3.Z).nor();
        if (electricSide.isZero()) { electricSide.set(Vector3.X); }
        electricUp.set(electricSide).crs(electricAxis).nor();
        electricStart.set(start);
        for (int segment = 1; segment <= segments; segment++) {
            float t = segment / (float) segments;
            float width = segment == segments ? 0 : spread;
            electricEnd.set(start).mulAdd(electricAxis, t)
                  .mulAdd(electricSide, (UnitAttack.noise(seed + segment * 31) * 2 - 1) * width)
                  .mulAdd(electricUp, (UnitAttack.noise(seed + segment * 53) * 2 - 1) * width * .5f);
            beams.ribbon(camera, electricStart, electricEnd, .4f, PPC, alpha);
            electricStart.set(electricEnd);
        }
    }

    /**
     * A TAG's laser designator. One that hits holds a thin red line on its target, pulsing, with a red spot where it
     * lands, until the attack ends; one that misses only flickers faintly past its target for a moment.
     */
    private void designator(Vector3 muzzle, Vector3 target, boolean hit) {
        float on = attack.seconds - UnitAttack.ANTICIPATION_SECONDS;
        if (on < 0) {
            return;
        }
        if (!hit) {
            if (on < DESIGNATOR_MISS_SECONDS) {
                beams.ribbon(camera, muzzle, target, GpuDesignators.WIDTH, GpuDesignators.LINE,
                      .35f * (1 - on / DESIGNATOR_MISS_SECONDS));
            }
            return;
        }
        float fadeIn = MathUtils.clamp(on / .08f, 0, 1);
        float fadeOut = MathUtils.clamp((attack.duration - attack.seconds) / .3f, 0, 1);
        float pulse = GpuDesignators.pulse(on);
        GpuDesignators.draw(beams, camera, muzzle, target, fadeIn * fadeOut * pulse, pulse);
    }

    /** Constant width even when a missed beam continues far off-board. */
    private void beam(Vector3 start, Vector3 finish, float width, float kind, float alpha) {
        beams.ribbon(camera, start, finish, width, kind, alpha, kind == LASER ? laser.toFloatBits() : Color.WHITE_FLOAT_BITS);
        float power = alpha * beamScale;
        if (kind == LASER) {
            beamLights(start, finish, width, laserLight, BEAM_LIGHT * power, STRIKE_LIGHT * power, weaponLights);
        } else {
            beamLights(start, finish, width, PPC_LIGHT, PPC_BEAM_LIGHT * power, PPC_STRIKE_LIGHT * power, weaponLights);
        }
    }

    /**
     * A beam lights all along its length, from the weapon to where it ends, as a tube as wide as the beam, and most
     * where it strikes.
     */
    static void beamLights(Vector3 start, Vector3 finish, float width, Vector3 color, float beam, float strike,
          List<GpuLavaLighting.Source> lights) {
        float size = BoardGeometry.width() * .35f;
        light(lights, start.cpy(), finish.cpy(), beam, color, size, width);
        light(lights, finish.cpy(), null, strike, color, size, 0);
    }

    /** A short stream of growing, rolling flame packets; all positions come from the shared attack clock. */
    private void flame(Vector3 start, Vector3 finish, float size) {
        direction.set(finish).sub(start);
        float time = (attack.seconds - UnitAttack.ANTICIPATION_SECONDS)
              / Math.max(.18f, attack.flightSeconds);
        float fade = MathUtils.clamp((UnitAttack.ANTICIPATION_SECONDS + attack.flightSeconds
              + UnitAttack.RECOVERY_SECONDS - attack.seconds) / .16f, 0, 1);
        float seed = (attack.event.result().id().hashCode() & 255) * .17f;
        flameSide.set(direction).crs(Vector3.Z).nor();
        if (flameSide.isZero()) { flameSide.set(Vector3.X); }
        flameUp.set(flameSide).crs(direction).nor();
        float burning = MathUtils.clamp(time, 0, 1) * fade;
        // The stream lights from the nozzle to its leading packet, growing with it, as wide as its packets midway.
        light(weaponLights, start.cpy(), start.cpy().mulAdd(direction, Math.min(1, time)), .9f * burning, FLAMER_LIGHT,
              size * 10, size * 2);
        // These wisps are transient exhaust; the game alone decides whether terrain actually catches fire.
        for (int puff = 0; puff < 6; puff++) {
            float age = time - .7f - puff * .12f;
            if (age <= 0 || age >= 1.2f) { continue; }
            point.set(start).mulAdd(direction, .8f + puff * .035f)
                  .mulAdd(flameSide, MathUtils.sin(seed + puff * 2.4f) * age * size)
                  .add(0, 0, age * size * 5);
            float radius = size * (1 + age * 3);
            flameWidth.set(camera.direction).crs(camera.up).nor().scl(radius);
            flameLength.set(camera.up).scl(radius);
            flames.quad(point, flameWidth, flameLength, -1, 0,
                  .16f * MathUtils.sin(age / 1.2f * MathUtils.PI) * fade);
        }
        for (int puff = 0; puff < FLAME_PUFFS; puff++) {
            float age = time - puff * (.85f / (FLAME_PUFFS - 1));
            if (age <= 0 || age >= 1.12f) { continue; }
            float travel = Math.min(1, age);
            float swirl = seed + puff * 2.399963f + age * 8;
            float spread = size * travel * 1.25f;
            point.set(start).mulAdd(direction, travel)
                  .mulAdd(flameSide, MathUtils.sin(swirl) * spread)
                  .mulAdd(flameUp, MathUtils.cos(swirl * 1.3f) * spread * .65f)
                  .add(0, 0, travel * travel * size * 1.5f);
            float radius = size * (.55f + travel * 3.4f) * (.85f + .15f * MathUtils.sin(swirl));
            flameWidth.set(direction).crs(camera.direction).nor();
            if (flameWidth.isZero()) { flameWidth.set(camera.up); }
            flameLength.set(camera.direction).crs(flameWidth).nor()
                  .scl(Math.max(radius * 1.35f, direction.len() / FLAME_PUFFS));
            flameWidth.scl(radius);
            float opacity = .8f * MathUtils.clamp(age * 16, 0, 1)
                  * (1 - MathUtils.clamp((age - 1) / .12f, 0, 1)) * fade;
            // Integer part identifies a packet; the fractional part is its normalized age for the flame shader.
            flames.quad(point, flameWidth, flameLength, -1, 2 + puff + age / 1.12f, opacity);
        }
    }

    /**
     * Transient presentation of the observed munition. Persistent fire, smoke and minefields belong to the board.
     *
     * @param center where the round's flight ended
     * @param ground the ground it was aimed at: {@code center} itself, unless it opened in the air there
     */
    private void impact(Vector3 center, Vector3 ground, float fade, ResolvedAttack.Shot profile) {
        var ammo = profile == null ? java.util.Set.<String>of() : profile.munitions();
        boolean smoke = ammo.contains("M_SMOKE") || ammo.contains("M_SMOKE_WARHEAD");
        boolean fire = ammo.contains("M_INFERNO") || ammo.contains("M_INFERNO_IV") || ammo.contains("M_INCENDIARY")
              || ammo.contains("M_INCENDIARY_LRM") || ammo.contains("M_THUNDER_INFERNO");
        int seed = attack.event.result().id().hashCode() ^ Float.floatToIntBits(center.x);
        // An artillery illumination round bursts as a star shell; a missile's or mortar's flare still glows.
        if (ammo.contains("M_FLARE") && !GpuArtilleryBlast.isStarShell(profile)) {
            glow(center, 1 + (1 - fade) * 2, FLARE_GLOW, 1 - fade, fade);
        }
        else if (GpuArtilleryBlast.drawsLanding(profile)) {
            boolean opened = !ground.equals(center);
            artilleryBlasts.detonate(ground, opened ? center : null, profile, attack, seed);
        }
        else if (!smoke) { explosions.burst(center, 5.5f, 1 - fade, seed); }
        if (smoke || fire) {
            for (int puff = 0; puff < 4; puff++) {
                float angle = puff * 2.399963f, spread = (1 - fade) * 8;
                point.set(center).add(MathUtils.cos(angle) * spread, MathUtils.sin(angle) * spread, spread * (1 + puff * .2f));
                explosions.add(point, 1.5f + spread * .6f, 1 - fade, fire ? .4f : 0, fade * .8f, seed + puff * 31);
            }
        }
    }

    /** Solid rounds spark only. Calibre sets their extent; each MG round has a short, separate contact pulse. */
    private void ballisticImpact(Vector3 origin, Vector3 center, float fade, ResolvedAttack.Shot profile, int seed, boolean targetHit) {
        if (targetHit ? attack.event.conventionalInfantryTarget() : !attack.hardImpact(center)) { return; }
        float age = 1 - fade;
        if (profile != null && profile.machineGun()) { age *= UnitAttack.RECOVERY_SECONDS / .13f; }
        if (age >= 1) { return; }
        // Square-root scaling keeps small rounds readable and larger racks visibly larger without a linear-size blast.
        int rack = Math.max(1, profile == null ? 5 : profile.rackSize());
        float size = 1.5f * (float) Math.sqrt(rack / 5f);
        impactSparks(origin, center, age, size, 4 + Math.min(rack, 28), seed, targetHit);
    }

    /** Short outward streaks with drag and gravity, sampled from each round's contact clock. No particle state. */
    private void impactSparks(Vector3 origin, Vector3 center, float age, float size, int count, int seed, boolean targetHit) {
        sparkAxis.set(origin).sub(center).nor();
        if (!targetHit || sparkAxis.isZero()) { sparkAxis.set(Vector3.Z); }
        sparkSide.set(sparkAxis).crs(Vector3.Z).nor();
        if (sparkSide.isZero()) { sparkSide.set(Vector3.X); }
        sparkUp.set(sparkSide).crs(sparkAxis).nor();
        float rotation = Math.floorMod(seed, 4096) * .17f;
        float tail = Math.max(0, age - .18f);
        for (int spark = 0; spark < count; spark++) {
            float angle = rotation + spark * 2.399963f;
            float spread = 7 + spark % 3 * 2;
            sparkVelocity.set(sparkAxis).scl(3 + spark % 4)
                  .mulAdd(sparkSide, MathUtils.cos(angle) * spread).mulAdd(sparkUp, MathUtils.sin(angle) * spread).scl(size);
            point.set(center).mulAdd(sparkVelocity, age * (1.5f - age * .5f)).add(0, 0, -size * age * age * 2.5f);
            end.set(center).mulAdd(sparkVelocity, tail * (1.5f - tail * .5f)).add(0, 0, -size * tail * tail * 2.5f);
            projectiles.ribbon(camera, end, point, size * .38f, IMPACT_SPARK + Math.min(age, .99f), 1 - age);
        }
        glow(center, size * 1.4f, CONTACT_GLOW, age, Math.max(0, 1 - age * 6));
    }

    private void glow(Vector3 center, float radius, int kind, float phase, float alpha) {
        projectiles.billboard(camera, center, radius, kind + MathUtils.clamp(phase, 0, .99f), alpha);
    }

    /** Links the programs of every weapon effect now (GpuBattleView's warm-up), not at the first shot in play. */
    void prepare() {
        flames.prepare();
        beams.prepare();
        projectiles.prepare();
        missiles.prepare();
        explosions.prepare();
    }

    @Override
    public void dispose() {
        attack = null;
        volley = List.of();
        instances = Map.of();
        library = null;
        launches.clear();
        traces.clear();
        groundComplete.clear();
        missiles.dispose();
        explosions.dispose();
        artilleryBlasts.clear();
        flames.dispose();
        beams.dispose();
        projectiles.dispose();
    }
}
