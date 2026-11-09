/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.DoubleFunction;
import java.util.function.Function;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import jakarta.annotation.Nullable;
import megamek.client.ui.clientGUI.boardview.BoardMarker;
import megamek.client.ui.clientGUI.boardview.gpu.GpuExplosionEffects.Look;
import megamek.common.ResolvedAttack;
import megamek.common.board.Coords;
import megamek.common.equipment.AmmoType;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.WeaponType;
import megamek.common.equipment.enums.BombType;
import megamek.common.weapons.handlers.AreaEffectHelper;
import megamek.common.weapons.handlers.DamageFalloff;
import megamek.common.weapons.handlers.NukeStats;
import megamek.logging.MMLogger;

/**
 * The ground blast of an artillery shell: a choreography of fire and smoke volumes, drawn by {@link GpuExplosionEffects},
 * that outlives the attack which caused it. An attack's own clock ends a third of a second after contact, but a shell's
 * fire, thrown earth and smoke column take several seconds to rise and drift away, so each blast keeps its own clock.
 *
 * <p>A blast is one of three shapes, chosen per shell so a barrage does not repeat itself: a crown of jagged spikes of
 * earth and fire with a dust skirt, a fireball rising into a mushroom on a thin stem, or a bright billowing fireball
 * over a dark base. The crown and the fireball leave a dark smoke mass that the wind carries off; the mushroom's cap
 * darkens into its own.</p>
 *
 * <p>The blast stays in the target hex. The hexes around it that the shell also damages each take a piece of debris,
 * thrown on its own arc and landing at its own moment, which leaves a scorch mark sized for that ring's damage.</p>
 */
final class GpuArtilleryBlast {
    private static final MMLogger LOGGER = MMLogger.create(GpuArtilleryBlast.class);

    /**
     * The blast shapes: the first three after the reference sheet the effect was drawn from, CLUSTER for a round that
     * bursts in the air and scatters bomblets over its target hex and the ring around it, and MINES for a round that
     * opens above the hex it lands on and lets its mines drift down into that hex alone. DUD for a round that came down
     * without going off, a homing round with no TAG to follow; FUEL_AIR for a fuel-air round; STAR_SHELL for an
     * illumination round, bursting high over its target to hang its flare.
     */
    enum Style { CROWN, MUSHROOM, FIREBALL, CLUSTER, MINES, DUD, FUEL_AIR, STAR_SHELL, NUCLEAR }

    /** How long the slowest layer of a blast (its smoke) lasts. */
    static final float SECONDS = 5.5f;
    /** A nuclear round's blast lasts longer: its mushroom keeps rising after the shock front has crossed the board. */
    static final float NUCLEAR_SECONDS = 18;
    /**
     * The shock front crosses a blast area of {@link #SHOCK_HEXES} hexes or more in this time; a smaller blast's front
     * crosses its area quicker ({@link #shockSeconds}).
     */
    static final float SHOCK_SECONDS = 2.4f;
    private static final float SHOCK_HEXES = 40;
    /**
     * Blast seconds per second of the playback clock: the playback runs at half real time at 1x
     * ({@link UnitMotion.Speed#rate}), and a blast at 1x keeps real time.
     */
    static final float SECONDS_PER_PLAYBACK = (float) (1 / UnitMotion.Speed.NORMAL.rate);
    /** The nuclear type an artillery round's Davy Crockett-M warhead detonates as (doNuclearExplosion). */
    static final int DAVY_CROCKETT_TYPE = 1;
    /** At most this many blasts draw at once. A heavier barrage reuses the oldest blast's slot. */
    static final int MAX_BLASTS = 6;
    /** At most this many live nuclear blasts move the plants and spread their scorch (terrain-shocks.glsl). */
    static final int MAX_SHOCKS = 4;
    /** At most this many pieces of debris per blast: every hex of four rings, a Cruise Missile/120's R4. */
    static final int MAX_DEBRIS = 60;
    /**
     * Artillery rounds that lay a minefield in the hex they land on (the server's deliverFASCAMMinefield and
     * deliverThunderVibraMinefield). Thunder Active-IV is listed for when it is implemented.
     */
    private static final Set<String> MINE_LAYING = Set.of("M_FASCAM", "M_VIBRABOMB_IV", "M_ACTIVE_IV");
    /** Rounds that do not explode on landing: they lay mines, screen with smoke or light the field. */
    private static final Set<String> NON_EXPLOSIVE = Set.of("M_FASCAM", "M_VIBRABOMB_IV", "M_ACTIVE_IV", "M_THUNDER",
          "M_THUNDER_ACTIVE", "M_THUNDER_AUGMENTED", "M_THUNDER_INFERNO", "M_THUNDER_VIBRABOMB", "M_SMOKE",
          "M_SMOKE_WARHEAD", "M_FLARE", "M_LASER_INHIB");
    /** Density points each mine drawn stands for: a density 20 field shows 8 mines, a density 5 one 2. */
    private static final float DENSITY_PER_MINE = 2.5f;
    private static final int FEWEST_MINES = 2;
    private static final int MOST_MINES = 12;
    /** The density of an Arrow IV FASCAM minefield, which does not follow its rack size. */
    private static final int ARROW_IV_DENSITY = 20;
    private static final int CLAN_ARROW_IV_DENSITY = 30;
    /** The density of the minefield the shader preview and review sheets drop: a Long Tom's FASCAM round. */
    private static final int PREVIEW_MINE_DENSITY = 25;
    /** A mine's radius, in hexes. */
    private static final float MINE_SIZE = .028f;
    /** Height of a minefield round's dispenser above its hex, in hexes: lower and quieter than a cluster air burst. */
    private static final float DISPENSER_HEXES = .9f;
    /** How far short of the hex centre the shell opens, in hexes, so its stream of mines passes over the middle. */
    private static final float DISPENSER_LEAD = .15f;
    /** How fast the opened shell carries on along its line of flight, in hexes a second. */
    private static final float DISPENSER_SPEED = 1.2f;
    /** Flight samples searched for the moment a round comes down to its opening height. */
    private static final int OPENING_SAMPLES = 64;
    /** Halvings that then pin that moment down. */
    private static final int OPENING_REFINEMENTS = 12;
    /**
     * The earliest share of its flight a round may open at: a flat path that never climbs past its opening height
     * would otherwise open by the launcher, so it opens over its target as before.
     */
    private static final float EARLIEST_OPENING = .5f;
    /** The heading of a blast drawn without an attack, such as in the shader preview: due east. Never modified. */
    private static final Vector3 DEFAULT_HEADING = new Vector3(1, 0, 0);
    /** Damage of the shell that draws at the reference size: a Sniper or an Arrow IV. */
    private static final float REFERENCE_DAMAGE = 20;
    /** The smallest and largest blasts, against the 20-damage one: a 7-damage round and a 32-damage one. */
    private static final float SMALLEST_SCALE = .35f;
    private static final float LARGEST_SCALE = 1.6f;
    /** Pieces a shell damaging only its own hex throws, all landing inside that hex. */
    private static final int TARGET_HEX_PIECES = 12;
    /** At most this many fragments land in one damaged hex; wide blasts share the debris budget more thinly. */
    private static final int MAX_FRAGMENTS_PER_HEX = 5;
    /** Any hex away from the board's edge: its neighbours sit at the same offsets as every other hex's. */
    private static final Coords LAYOUT_HEX = new Coords(10, 10);

    private static final Look FIRE = new Look(.85f, .75f, .1f);
    private static final Look BRIGHT_FIRE = new Look(.55f, 1, 0);
    private static final Look EARTH = new Look(.35f, 0, .9f);
    /** A fuel-air round's dispersed fuel: a pale, low vapour before it lights. */
    private static final Look FUEL_HAZE = new Look(0, 0, .15f);
    /** Its ignition and roiling column: violent fire, churning and flecked with burning metal. */
    private static final Look FUEL_FIRE = new Look(.35f, .6f, 0, 1);
    private static final Look FUEL_COLUMN = new Look(.9f, .75f, .05f, 1);
    /** The heavy black soot it leaves, still churning a little. */
    private static final Look FUEL_SOOT = new Look(1, .2f, 0, .5f);
    /** Burning chunks it throws out. */
    private static final Look FUEL_CHUNK = new Look(.2f, 1, 0, .6f);
    /**
     * Seconds after landing a fuel-air round's dispersed fuel lights; its terrain changes show then too. Half a second
     * lets the spreading cloud read as fuel before it goes up.
     */
    static final float FUEL_AIR_IGNITION_SECONDS = .5f;
    /** How long a fuel-air round's haze takes to burn away under its sheet of fire. */
    private static final float FUEL_HAZE_LINGER_SECONDS = .2f;
    /** How dense a fuel-air round's haze is at its thickest. */
    private static final float FUEL_HAZE_OPACITY = .55f;
    /** How strongly a fuel-air blast draws in thin air, where it does half damage. */
    private static final float THIN_AIR_INTENSITY = .65f;
    private static final int FUEL_AIR_CHUNKS = 12;
    /** The hex a fuel-air blast drawn without an attack, such as in the shader preview, is laid out around. */
    private static final Coords PREVIEW_HEX = new Coords(6, 6);
    /** Clods of earth a dud throws up where it strikes. */
    private static final int DUD_CLODS = 7;
    private static final Look SMOKE = new Look(1, .45f, .1f);
    private static final Look CAP = new Look(.9f, .4f, 0);
    private static final Look DEBRIS = new Look(.8f, .3f, .6f);
    private static final Look BURST_SMOKE = new Look(.55f, .1f, 0);
    private static final Look PALE_SMOKE = new Look(0, 0, .1f);
    /** Darkest soot with no ember: a mine reads as a small solid dark shape. */
    private static final Look MINE = new Look(1, 0, 0);
    /** Height of a cluster round's air burst above its target, in hexes. */
    private static final float AIR_BURST_HEXES = 1.3f;
    /** A cluster round's damage that one bomblet stands for: a 20-damage hex gets five, a 5-damage hex two. */
    private static final float DAMAGE_PER_BOMBLET = 4;
    /** Bomblets drawn for each one the damage alone calls for. */
    private static final float BOMBLET_DENSITY = 1.5f;
    /** The cluster round the shader preview shows: a Long Tom's, 20/10 over one ring. */
    static final AreaEffect PREVIEW_CLUSTER = new AreaEffect(1, 20, 10, true);

    private final List<Blast> blasts = new ArrayList<>();
    /** How fuel-air rounds burn in the board's atmosphere, which the rules and this blast both follow. */
    private AreaEffectHelper.FuelAirStrength fuelAirStrength = AreaEffectHelper.FuelAirStrength.FULL;
    /** The mines lying in each known minefield hex, placed on the installed terrain once, by hex. */
    private final Map<Coords, List<Vector3>> restingMines = new HashMap<>();
    private final Vector3 point = new Vector3();
    private final Vector3 axis = new Vector3();
    private final Vector3 burst = new Vector3();

    /**
     * The area a shell damages, as the Artillery Ordnance Table gives it: a Long Tom's high-explosive round is
     * 25/15/5 (R2), so {@code damage} 25 in the target hex, {@code falloff} 10 less for each ring, {@code hexRadius} 2.
     *
     * @param hexRadius rings of hexes around the target that take damage
     * @param damage    damage in the target hex
     * @param falloff   damage lost for each ring outward
     * @param cluster   whether the round scatters bomblets instead of blasting once
     */
    record AreaEffect(int hexRadius, int damage, int falloff, boolean cluster) {
        static final AreaEffect TARGET_HEX_ONLY = new AreaEffect(0, 0, 10, false);

        /** The damage a hex {@code ring} rings out takes, never below 1 so its mark stays visible. */
        int ringDamage(int ring) { return Math.max(1, damage - falloff * ring); }
    }

    /**
     * One piece of debris, thrown at one hex.
     *
     * @param offsetX how far east of the target hex's centre it lands, in world units
     * @param offsetY how far north of the target hex's centre it lands, in world units
     * @param ring    the ring of hexes it lands in: 0 is the target hex
     * @param launch  seconds after contact it leaves the crater
     * @param flight  seconds it spends in the air
     * @param marksGround whether it leaves a scorch mark where it lands: one piece per damaged hex does, so a dense
     *                    spray does not turn into a dense carpet of marks
     */
    record Piece(float offsetX, float offsetY, int ring, float launch, float flight, boolean marksGround) {
        float landing() { return launch + flight; }
    }

    /** One shell's blast, as captured at contact, with its own clock in seconds. */
    private static final class Blast {
        final Vector3 ground = new Vector3();
        final float size;
        final AreaEffect area;
        final Style style;
        final int key;
        /** The shot, {@code null} for a blast drawn without one. */
        final ResolvedAttack.Shot profile;
        /** The attack that landed the shell, {@code null} for a blast drawn without one. */
        final UnitAttack attack;
        /** The hex the shell landed in, {@code null} for a blast drawn without an attack. */
        final Coords targetHex;
        /**
         * The seed its shapes are drawn with: a minefield round's is its hex's, so its mines lie where the mines
         * drawn for the minefield afterwards lie, in the same shapes; any other blast's is its key.
         */
        final int drawSeed;
        final List<Piece> pieces;
        /** Where each piece lands, on the terrain where it could be found, else level with the crater. */
        final List<Vector3> landings;
        /** Whether each landing was found on the terrain, which a scorch mark needs. */
        final boolean[] onTerrain;
        /** The shell's direction of travel across the ground, which a minefield round's mines stream out along. */
        final Vector3 heading;
        /**
         * Where the round opened in the air, its flight having ended there; {@code null} when it came down to the
         * ground, and a cluster burst or minefield dispenser opens above its target as drawn without a flight.
         */
        final Vector3 opening;
        /** How strongly it draws: 1, or less for a fuel-air round in thin air. */
        final float intensity;
        float seconds;
        /** One bit per piece of debris that has landed, whether or not it left a mark; any radius has room. */
        final java.util.BitSet landed = new java.util.BitSet();
        int marks;
        boolean marksReported;
        /** A nuclear round's stats (its blast area, crater), null for every other round. */
        NukeStats nuke;
        /** An orbital bombardment's blast: no mushroom cloud, a lance of fire from the sky (GpuArtilleryBlast#nuclear). */
        boolean orbital;
        /** Its scorch has been handed to the terrain (scorches). */
        boolean scorched;
        /** Its attack's collapses have been handed to the terrain (strikes). */
        boolean struck;

        Blast(Vector3 ground, @Nullable Vector3 opening, @Nullable ResolvedAttack.Shot profile,
              @Nullable UnitAttack attack, int key, AreaEffectHelper.FuelAirStrength fuelAirStrength,
              @Nullable NukeStats scheduledNuke) {
            this.ground.set(ground);
            this.opening = opening == null ? null : opening.cpy();
            this.profile = profile;
            this.attack = attack;
            this.key = key;
            size = size(profile);
            area = areaEffect(weaponName(attack), profile);
            boolean fuelAir = isFuelAir(profile);
            // A fuel-air round in vacuum or thinner than thin air does nothing, as the rules say: a dud.
            boolean noFuelAirBurn = fuelAir && (fuelAirStrength == AreaEffectHelper.FuelAirStrength.NONE);
            style = scheduledNuke != null ? Style.NUCLEAR : (isDud(profile) || noFuelAirBurn) ? Style.DUD : isStarShell(profile) ? Style.STAR_SHELL
                  : laysMines(profile) ? Style.MINES : area.cluster() ? Style.CLUSTER : fuelAir ? Style.FUEL_AIR
                  : style(profile, key);
            intensity = (fuelAirStrength == AreaEffectHelper.FuelAirStrength.HALF) && fuelAir ? THIN_AIR_INTENSITY : 1;
            targetHex = landingHex(profile, attack);
            drawSeed = ((style == Style.MINES) && (targetHex != null)) ? mineSeed(targetHex) : key;
            pieces = switch (style) {
                case MINES -> mines(drawSeed, minesFor(minefieldDensity(weaponName(attack), profile)));
                case CLUSTER -> bomblets(area, key);
                case DUD, STAR_SHELL, NUCLEAR -> List.of();
                case FUEL_AIR -> fuelAirHexes(targetHex, area.hexRadius());
                default -> debrisPieces(area.hexRadius(), key);
            };
            nuke = scheduledNuke != null ? scheduledNuke : style == Style.NUCLEAR ? nuclearStats(profile) : null;
            Vector3 hexCentre = targetHex == null ? ground : BoardGeometry.center(targetHex, 0);
            heading = attack == null ? DEFAULT_HEADING
                  : heading(BoardGeometry.center(attack.event.attacker().location().coords(), 0), hexCentre);
            landings = new ArrayList<>(pieces.size());
            onTerrain = new boolean[pieces.size()];
            for (int index = 0; index < pieces.size(); index++) {
                Piece piece = pieces.get(index);
                float x = hexCentre.x + piece.offsetX();
                float y = hexCentre.y + piece.offsetY();
                Vector3 terrain = attack == null || attack.landscape == null ? null
                      : terrain(attack.landscape, x, y, ground.z);
                onTerrain[index] = terrain != null;
                landings.add(terrain != null ? terrain : new Vector3(x, y, ground.z));
            }
        }
    }

    /**
     * The hex a round came down on: where the server placed it, which for a round that missed is the hex it drifted to,
     * else the hex it was fired at. Its bomblets, debris, mines and burning hexes all lie around this hex; nothing is
     * drawn at the hex a missed round was aimed at.
     *
     * @return the hex, or {@code null} for a blast drawn without an attack
     */
    static @Nullable Coords landingHex(@Nullable ResolvedAttack.Shot profile, @Nullable UnitAttack attack) {
        if (attack == null) {
            return null;
        }
        boolean isPlacedByServer = (profile != null) && (profile.impact() != null);
        return isPlacedByServer ? profile.impact().coords() : attack.event.destination().coords();
    }

    /**
     * Whether the shot's landing should draw a blast: an artillery round, but not one that lays mines, screens with
     * smoke or lights the field, which keep their own presentation.
     */
    static boolean explodes(@Nullable ResolvedAttack.Shot profile) {
        if (profile == null || (!profile.artillery() && nuclearStats(profile) == null)) { return false; }
        for (String munition : profile.munitions()) {
            if (NON_EXPLOSIVE.contains(munition)) { return false; }
        }
        return true;
    }

    /** Whether the shot is an artillery round that lays a minefield in the hex it lands on. */
    static boolean laysMines(@Nullable ResolvedAttack.Shot profile) {
        if (profile == null || !profile.artillery()) { return false; }
        for (String munition : profile.munitions()) {
            if (MINE_LAYING.contains(munition)) { return true; }
        }
        return false;
    }

    /** Whether the shot's landing is drawn here at all: a blast, a cluster round or a minefield being laid. */
    static boolean drawsLanding(@Nullable ResolvedAttack.Shot profile) {
        return explodes(profile) || laysMines(profile) || isStarShell(profile);
    }

    /** Whether the round is an illumination round (a star shell), which bursts high to hang a flare. */
    static boolean isStarShell(@Nullable ResolvedAttack.Shot profile) {
        return (profile != null) && profile.artillery() && profile.munitions().contains("M_FLARE");
    }

    /** Whether the landing leaves no single crater: a cluster round's bomblets, or mines, which make none. */
    static boolean leavesNoCrater(@Nullable String weaponName, @Nullable ResolvedAttack.Shot profile) {
        return laysMines(profile) || scattersBomblets(weaponName, profile) || isDud(profile) || isFuelAir(profile)
              || isStarShell(profile);
    }

    /** Whether the round is a fuel-air round that goes off: it burns every hex in its radius rather than digging. */
    static boolean isFuelAir(@Nullable ResolvedAttack.Shot profile) {
        return explodes(profile) && profile.munitions().contains("M_FAE") && !isDud(profile);
    }

    /** Sets how fuel-air rounds burn in the board's atmosphere ({@link AreaEffectHelper#fuelAirStrength}). */
    void setFuelAirStrength(AreaEffectHelper.FuelAirStrength strength) {
        fuelAirStrength = strength;
    }

    /**
     * Whether the round came down without going off: an explosive artillery round the server sent as dealing no
     * damage, which it does only for a homing round with no TAG to follow. It throws up dirt and leaves no crater.
     */
    static boolean isDud(@Nullable ResolvedAttack.Shot profile) {
        return explodes(profile) && nuclearStats(profile) == null && (profile.damagePerHit() == 0);
    }

    /**
     * The radius, in world units, of the blast of this shell. A 20-damage shell spans about a hex; lighter and heavier
     * shells scale in step with their damage, so a Long Tom (25) draws well over a Thumper (15) and over three times a
     * Thumper Cannon (5), within limits that keep a cannon round visible and a Long Tom on the map.
     */
    static float size(@Nullable ResolvedAttack.Shot profile) {
        double damage = profile == null || profile.damagePerHit() <= 0 ? REFERENCE_DAMAGE : profile.damagePerHit();
        float scale = MathUtils.clamp((float) (damage / REFERENCE_DAMAGE), SMALLEST_SCALE, LARGEST_SCALE);
        if (nuclearStats(profile) != null) { scale = 2.5f; }
        else if (profile != null && profile.munitions().contains("M_FAE")) { scale *= 1.3f; }
        return BoardGeometry.height() * .55f * scale;
    }

    /**
     * The shape of the blast: a nuclear round always mushrooms, a fuel-air round burns as one, and anti-air rounds and
     * inferno bombs burn as a fireball.
     */
    static Style style(@Nullable ResolvedAttack.Shot profile, int seed) {
        Set<String> munitions = profile == null ? Set.of() : profile.munitions();
        if (nuclearStats(profile) != null) { return Style.NUCLEAR; }
        if (munitions.contains("M_FAE")) { return Style.FUEL_AIR; }
        if (munitions.contains("M_ADA") || munitions.contains("M_INFERNO")) { return Style.FIREBALL; }
        // Crowns are what a shell landing in earth mostly throws up; the other two keep a barrage varied.
        float pick = random(seed, 7);
        return pick < .5f ? Style.CROWN : pick < .75f ? Style.MUSHROOM : Style.FIREBALL;
    }

    /** Uses the server-observed ammunition type, retaining support for older Davy Crockett profiles. */
    static @Nullable NukeStats nuclearStats(@Nullable ResolvedAttack.Shot profile) {
        if (profile == null) { return null; }
        Integer type = profile.nuclearType();
        if (type == null && profile.munitions().contains("M_DAVY_CROCKETT_M")) { type = DAVY_CROCKETT_TYPE; }
        return type == null ? null : AreaEffectHelper.getNukeStats(type);
    }

    /**
     * The area the shell damages, with the R value of the Artillery Ordnance Table: 0 is the target hex alone, 2 is a
     * Long Tom's target hex and the two rings around it. It is asked of the same rule the server deals the damage with,
     * for the round whose munitions the shot carried, or for a dropped bomb (its mount fires the landing).
     *
     * @param weaponName the internal name of the weapon that fired or the bomb dropped, or {@code null} when unknown
     * @param profile    the shot, or {@code null} when unknown
     *
     * @return the area; {@link AreaEffect#TARGET_HEX_ONLY} when the weapon or its round cannot be found
     */
    static AreaEffect areaEffect(@Nullable String weaponName, @Nullable ResolvedAttack.Shot profile) {
        if (weaponName == null || profile == null) { return AreaEffect.TARGET_HEX_ONLY; }
        if (EquipmentType.get(weaponName) instanceof BombType bomb) {
            DamageFalloff falloff = AreaEffectHelper.calculateDamageFallOff(bomb, 0, false);
            return new AreaEffect(Math.max(0, falloff.radius), falloff.damage, falloff.falloff,
                  falloff.clusterMunitionsFlag);
        }
        if (!(EquipmentType.get(weaponName) instanceof WeaponType weapon)) {
            LOGGER.debug("[ArtilleryBlast] {} is not a weapon, the blast stays in its hex", weaponName);
            return AreaEffect.TARGET_HEX_ONLY;
        }
        AmmoType round = round(weapon, profile.munitions());
        if (round == null) {
            LOGGER.debug("[ArtilleryBlast] no {} round carries {}, the blast stays in its hex", weaponName,
                  profile.munitions());
            return AreaEffect.TARGET_HEX_ONLY;
        }
        DamageFalloff falloff = AreaEffectHelper.calculateDamageFallOff(round, 0, false);
        // A homing round strikes the unit it homes on and splashes only that unit's hex
        // (ArtilleryWeaponDistantHomingHandler), not the ring around it.
        boolean isHoming = profile.munitions().contains("M_HOMING");
        int radius = isHoming ? 0 : Math.max(0, falloff.radius);
        return new AreaEffect(radius, falloff.damage, falloff.falloff, falloff.clusterMunitionsFlag);
    }

    /** The round of the weapon's ammunition family whose munitions are exactly those the shot carried. */
    private static @Nullable AmmoType round(WeaponType weapon, Set<String> munitions) {
        AmmoType standard = null;
        List<AmmoType> rounds = AmmoType.getMunitionsFor(weapon.getAmmoType());
        if (rounds == null) {
            // An aircraft's Arrow IV homing missile, for one, has no list of munitions to choose from.
            LOGGER.debug("[ArtilleryBlast] {} has no munitions list, its round is unknown", weapon.getInternalName());
            return null;
        }
        for (AmmoType candidate : rounds) {
            Set<String> names = new HashSet<>();
            for (AmmoType.Munitions munition : candidate.getMunitionType()) { names.add(munition.name()); }
            if (names.equals(munitions)) { return candidate; }
            if (standard == null && names.contains("M_STANDARD")) { standard = candidate; }
        }
        // A shot captured without its munitions is a standard round.
        return munitions.isEmpty() ? standard : null;
    }

    /**
     * The debris a shell throws: a spray of small fragments at every hex of every ring it damages, each landing
     * somewhere inside its hex and at its own moment, the outer rings a little later than the inner ones. Each hex
     * takes as many fragments as the budget allows, up to five, and the first of them leaves the hex's scorch mark. A
     * shell that damages only its own hex throws a spray that stays inside it.
     */
    static List<Piece> debrisPieces(int hexRadius, int seed) {
        List<Piece> pieces = new ArrayList<>();
        float hex = BoardGeometry.height();
        if (hexRadius == 0) {
            for (int piece = 0; piece < TARGET_HEX_PIECES; piece++) {
                float bearing = piece * 2.399963f + random(seed, piece + 200);
                float distance = hex * (.1f + .3f * random(seed, piece + 220));
                pieces.add(new Piece(MathUtils.cos(bearing) * distance, MathUtils.sin(bearing) * distance, 0,
                      launch(seed, piece), fragmentFlight(0, seed, piece), false));
            }
            return pieces;
        }
        int hexes = 3 * hexRadius * (hexRadius + 1);
        int perHex = MathUtils.clamp(MAX_DEBRIS / hexes, 1, MAX_FRAGMENTS_PER_HEX);
        Vector3 centre = BoardGeometry.center(LAYOUT_HEX, 0);
        for (int ring = 1; ring <= hexRadius; ring++) {
            for (Coords coords : LAYOUT_HEX.allAtDistance(ring)) {
                Vector3 hexCentre = BoardGeometry.center(coords, 0);
                for (int fragment = 0; fragment < perHex; fragment++) {
                    int piece = pieces.size();
                    if (piece == MAX_DEBRIS) { return pieces; }
                    // Somewhere in the hex rather than its exact centre, so neither the spray nor the marks form a
                    // neat grid. Within a circle that fits inside the hex, so no fragment strays into a neighbour;
                    // the marking fragment stays nearer the middle so its mark sits clearly in its hex.
                    float spread = hex * (fragment == 0 ? .25f : .4f) * (float) Math.sqrt(random(seed, piece + 300));
                    float bearing = random(seed, piece + 320) * MathUtils.PI2;
                    float offsetX = hexCentre.x - centre.x + MathUtils.cos(bearing) * spread;
                    float offsetY = hexCentre.y - centre.y + MathUtils.sin(bearing) * spread;
                    float flight = fragmentFlight(ring, seed, piece);
                    pieces.add(new Piece(offsetX, offsetY, ring, launch(seed, piece), flight, fragment == 0));
                }
            }
        }
        return pieces;
    }

    /**
     * The bomblets of a cluster round: a few in every hex it damages, more where it deals more damage, each landing
     * somewhere inside its hex. They leave the casing over a quarter second and fall for different times, so their
     * pops ripple across the area for about a second instead of going off together.
     */
    static List<Piece> bomblets(AreaEffect area, int seed) {
        List<Piece> pieces = new ArrayList<>();
        float hex = BoardGeometry.height();
        Vector3 centre = BoardGeometry.center(LAYOUT_HEX, 0);
        for (int ring = 0; ring <= area.hexRadius(); ring++) {
            List<Coords> hexes = ring == 0 ? List.of(LAYOUT_HEX) : LAYOUT_HEX.allAtDistance(ring);
            // The ring's whole share, spread as evenly as whole bomblets allow: a share of 4.5 per hex gives three
            // hexes five and three hexes four, rather than rounding every hex the same way.
            int ringTotal = Math.round(bombletsPerHex(area.ringDamage(ring)) * hexes.size());
            for (int hexIndex = 0; hexIndex < hexes.size(); hexIndex++) {
                Vector3 hexCentre = BoardGeometry.center(hexes.get(hexIndex), 0);
                int inHex = ringTotal / hexes.size() + (hexIndex < ringTotal % hexes.size() ? 1 : 0);
                for (int bomblet = 0; bomblet < inHex; bomblet++) {
                    int piece = pieces.size();
                    if (piece == MAX_DEBRIS) { return pieces; }
                    float bearing = random(seed, piece + 400) * MathUtils.PI2;
                    // Square root spreads the bomblets evenly over the hex rather than crowding its centre.
                    float out = hex * .38f * (float) Math.sqrt(random(seed, piece + 420));
                    float offsetX = hexCentre.x - centre.x + MathUtils.cos(bearing) * out;
                    float offsetY = hexCentre.y - centre.y + MathUtils.sin(bearing) * out;
                    float release = .08f + .25f * random(seed, piece + 440);
                    float fall = .35f + .3f * random(seed, piece + 460);
                    pieces.add(new Piece(offsetX, offsetY, ring, release, fall, true));
                }
            }
        }
        return pieces;
    }

    /**
     * The mines of a minefield round: a handful that drift down into the hex the round landed on, never into a
     * neighbour, spread over most of it. They leave the dispenser over a quarter second and fall slowly, settling over
     * about a second. A smaller count draws the first mines of the same seed's larger one, so a minefield thinned by
     * detonations loses mines rather than moving them.
     *
     * @param count the number of mines, from {@link #minesFor(int)}
     */
    static List<Piece> mines(int seed, int count) {
        List<Piece> pieces = new ArrayList<>();
        float hex = BoardGeometry.height();
        for (int mine = 0; mine < count; mine++) {
            float bearing = mine * 2.399963f + random(seed, mine + 500);
            // Inside a circle that fits the hex, spread evenly over it rather than crowding its centre.
            float out = hex * .4f * (float) Math.sqrt(MathUtils.lerp(.05f, 1, random(seed, mine + 520)));
            // They leave in turn over a quarter second, so the shell trails them out as a stream.
            float release = .05f + .25f * mine / Math.max(1, count - 1) + .02f * random(seed, mine + 540);
            float fall = .8f + .4f * random(seed, mine + 560);
            pieces.add(new Piece(MathUtils.cos(bearing) * out, MathUtils.sin(bearing) * out, 0, release, fall, false));
        }
        return pieces;
    }

    /**
     * The density of the minefield a minefield round lays, as the server works it out: the rack size of its shell,
     * except an Arrow IV's, which lays 20 from an Inner Sphere launcher and 30 from a Clan one.
     *
     * @param weaponName the internal name of the weapon that fired, or {@code null} when unknown
     * @param profile    the shot, or {@code null} when unknown
     */
    static int minefieldDensity(@Nullable String weaponName, @Nullable ResolvedAttack.Shot profile) {
        if ((weaponName != null) && (EquipmentType.get(weaponName) instanceof WeaponType weapon)
              && (weapon.getAmmoType() == AmmoType.AmmoTypeEnum.ARROW_IV)) {
            return weapon.isClan() ? CLAN_ARROW_IV_DENSITY : ARROW_IV_DENSITY;
        }
        return profile == null ? 0 : profile.rackSize();
    }

    /** The internal name of the weapon that fired the attack, or {@code null} for a blast drawn without one. */
    private static @Nullable String weaponName(@Nullable UnitAttack attack) {
        return attack == null ? null : attack.event.result().equipmentName();
    }

    /**
     * The mines drawn for a minefield of {@code density}: one for every {@link #DENSITY_PER_MINE} points, at least
     * {@link #FEWEST_MINES} and at most {@link #MOST_MINES}. A minefield round's drop takes the density of the field it
     * lays ({@link #minefieldDensity}), so it shows as many mines as then lie in the hex.
     */
    static int minesFor(int density) {
        return MathUtils.clamp(Math.round(density / DENSITY_PER_MINE), FEWEST_MINES, MOST_MINES);
    }

    /**
     * Bomblets for a hex taking {@code damage}, on average: one for about every four points, at least two and at most
     * five, then half as many again ({@link #BOMBLET_DENSITY}) so the area reads as densely covered.
     */
    static float bombletsPerHex(int damage) {
        return MathUtils.clamp(Math.round(damage / DAMAGE_PER_BOMBLET), 2, 5) * BOMBLET_DENSITY;
    }

    /** Whether the shot is a cluster round, which scatters bomblets and leaves no single crater. */
    static boolean scattersBomblets(@Nullable String weaponName, @Nullable ResolvedAttack.Shot profile) {
        return explodes(profile) && areaEffect(weaponName, profile).cluster();
    }

    /** Fragments leave the burst within its first tenth of a second. */
    private static float launch(int seed, int piece) {
        return .02f + .08f * random(seed, piece + 240);
    }

    /** Seconds a fragment flies to a hex {@code ring} rings out: a fraction of a second, a little longer farther out. */
    private static float fragmentFlight(int ring, int seed, int piece) {
        return .07f + .1f * ring + .06f * random(seed, piece + 260);
    }

    /**
     * Starts the blast of a shell landing at {@code ground}. The impact is reported on every frame of the attack's
     * contact window, so a {@code key} already blasting is ignored.
     *
     * @param attack the attack that landed the shell, which names the weapon, the target hex and the terrain for the
     *               debris, or {@code null} for a blast with none of them
     */
    void detonate(Vector3 ground, @Nullable ResolvedAttack.Shot profile, @Nullable UnitAttack attack, int key) {
        detonate(ground, null, profile, attack, key);
    }

    /**
     * Starts the blast of a round that opened in the air at {@code opening}, its flight ended there, over its target
     * at {@code ground}: a cluster round bursts and a minefield round's dispenser opens at that point.
     *
     * @param opening where the round opened, or {@code null} for a round that came down to the ground
     */
    void detonate(Vector3 ground, @Nullable Vector3 opening, @Nullable ResolvedAttack.Shot profile,
          @Nullable UnitAttack attack, int key) {
        detonate(ground, opening, profile, attack, key, null);
    }

    /** Scheduled stock/custom strike using its server-resolved blast parameters, with no invented weapon or unit. */
    void detonateNuclear(Vector3 ground, NukeStats stats, int key) {
        detonateNuclear(ground, stats, key, false);
    }

    /** With {@code orbital} an orbital bombardment's: a lance of fire from the sky instead of a mushroom cloud. */
    void detonateNuclear(Vector3 ground, NukeStats stats, int key, boolean orbital) {
        detonate(ground, null, null, null, key, stats);
        for (Blast blast : blasts) {
            if (blast.key == key) { blast.orbital = orbital; }
        }
    }

    private void detonate(Vector3 ground, @Nullable Vector3 opening, @Nullable ResolvedAttack.Shot profile,
          @Nullable UnitAttack attack, int key, @Nullable NukeStats scheduledNuke) {
        for (Blast blast : blasts) {
            if (blast.key == key) { return; }
        }
        if (blasts.size() == MAX_BLASTS) {
            // A nuclear front times what it brings down: the oldest other blast makes way first.
            Blast oldest = blasts.stream().filter(blast -> blast.nuke == null).findFirst().orElse(blasts.getFirst());
            LOGGER.debug("[ArtilleryBlast] {} blasts already drawing, the oldest makes way", MAX_BLASTS);
            blasts.remove(oldest);
        }
        var blast = new Blast(ground, opening, profile, attack, key, fuelAirStrength, scheduledNuke);
        blasts.add(blast);
        LOGGER.info("[ArtilleryBlast] {} blast of radius {} with {} pieces of debris over R{} at {}", blast.style,
              blast.size, blast.pieces.size(), blast.area.hexRadius(), ground);
    }

    /**
     * How far above its target a round opens in the air: a cluster round bursts at {@link #AIR_BURST_HEXES} and a
     * minefield round's dispenser opens at {@link #DISPENSER_HEXES}. A round that bursts on the ground opens at none.
     *
     * @param weaponName the internal name of the weapon that fired, or {@code null} when unknown
     * @param profile    the shot, or {@code null} when unknown
     *
     * @return the height in board units, {@code 0} for a round that comes down to the ground
     */
    static float openingHeight(@Nullable String weaponName, @Nullable ResolvedAttack.Shot profile) {
        if (laysMines(profile)) {
            return BoardGeometry.height() * DISPENSER_HEXES;
        }
        if (scattersBomblets(weaponName, profile)) {
            return BoardGeometry.height() * AIR_BURST_HEXES;
        }
        if (isStarShell(profile)) {
            return BoardGeometry.height() * GpuStarShells.HANG_HEXES;
        }
        return 0;
    }

    /**
     * The share of its flight at which a round coming down onto {@code aim} reaches {@code height} above it, where it
     * opens and its flight ends.
     *
     * @param path   the round's position at each share of its flight, from {@code 0} to {@code 1}
     * @param aim    where the flight would end on the ground
     * @param height the opening height above {@code aim}, from {@link #openingHeight}
     *
     * @return the share of the flight, or {@code -1} when the round opens on the ground: no opening height, or a
     *       flight that does not come down from above it in its second half
     */
    static float openingProgress(DoubleFunction<Vector3> path, Vector3 aim, float height) {
        if (height <= 0) {
            return -1;
        }
        float level = aim.z + height;
        // The last sample still above the opening height; the flight comes down through it after that.
        int above = -1;
        for (int sample = OPENING_SAMPLES; sample >= 0; sample--) {
            if (path.apply((double) sample / OPENING_SAMPLES).z >= level) {
                above = sample;
                break;
            }
        }
        if ((above < 0) || (above == OPENING_SAMPLES)) {
            return -1;
        }
        float high = (float) above / OPENING_SAMPLES;
        float low = (float) (above + 1) / OPENING_SAMPLES;
        for (int refinement = 0; refinement < OPENING_REFINEMENTS; refinement++) {
            float middle = (high + low) / 2;
            if (path.apply(middle).z >= level) {
                high = middle;
            } else {
                low = middle;
            }
        }
        return high < EARLIEST_OPENING ? -1 : high;
    }

    /** Ages every blast and retires those whose smoke has cleared. */
    void advance(float seconds) {
        for (Blast blast : blasts) { blast.seconds += seconds; }
        blasts.removeIf(blast -> blast.seconds >= (blast.nuke != null ? NUCLEAR_SECONDS : SECONDS));
    }

    /**
     * Hands each nuclear blast's scorch to the terrain once, as it goes off: its blast area is the round's Base Damage
     * over its Degradation in hexes, where the damage reaches nothing, and its crater twice its Crater Depth (the rules'
     * one level less every two hexes).
     */
    /**
     * Each nuclear round's blast, once, as it goes off: its attack's result and the hex it landed in, whose collapses
     * fall with its front (GpuTerrain#strike).
     */
    void strikes(java.util.function.BiConsumer<megamek.common.ResolvedAttack, Coords> receiver) {
        for (Blast blast : blasts) {
            if (blast.struck || blast.nuke == null || blast.attack == null || blast.targetHex == null || blast.seconds < 0) {
                continue;
            }
            blast.struck = true;
            var result = blast.attack.event.result();
            if (!result.collapses().isEmpty()) { receiver.accept(result, blast.targetHex); }
        }
    }

    void scorches(Consumer<GpuGroundDamage.Scorch> receiver) {
        for (Blast blast : blasts) {
            if (blast.nuke == null || blast.scorched || blast.seconds < 0) { continue; }
            blast.scorched = true;
            receiver.accept(new GpuGroundDamage.Scorch(blast.ground.cpy(), nuclearRadius(blast.nuke),
                  2 * blast.nuke.craterDepth * BoardGeometry.height()));
        }
    }

    /**
     * A nuclear blast's flash on the whole view (GpuAtmosphere.setFlash): {@code intensity} in display-linear light,
     * {@code warmth} from the white-hot instant (0) to the orange fireball (1), centred on the fireball.
     */
    record Flash(float intensity, float warmth, Vector3 centre) {
        static final Flash NONE = new Flash(0, 0, Vector3.Zero);
    }

    /**
     * The brightest nuclear flash now: blinding within a twentieth of a second, most of it gone in a second as the
     * fireball takes over, a warm afterglow fading over the next few.
     */
    Flash flash() {
        Flash brightest = Flash.NONE;
        for (Blast blast : blasts) {
            float seconds = blast.seconds;
            if (blast.nuke == null || seconds < 0 || seconds > 6) { continue; }
            float rise = Math.min(1, seconds / .05f), after = Math.max(0, seconds - .05f);
            float intensity = 6 * rise * (.8f * (float) Math.exp(-after / .18f) + .2f * (float) Math.exp(-after / 1.2f));
            if (intensity > brightest.intensity()) {
                float radius = nuclearRadius(blast.nuke);
                brightest = new Flash(intensity, MathUtils.clamp(seconds / 1.2f, 0, 1),
                      blast.ground.cpy().add(0, 0, radius * .1f));
            }
        }
        return brightest;
    }

    /**
     * The blast seconds a shock front takes to cross a blast area of {@code radius} world units: {@link #SHOCK_SECONDS}
     * from {@link #SHOCK_HEXES} hexes, less for a smaller blast, at least half of it.
     */
    static float shockSeconds(float radius) {
        return SHOCK_SECONDS * MathUtils.clamp((float) Math.sqrt(radius / BoardGeometry.height() / SHOCK_HEXES), .5f, 1);
    }

    /**
     * How far a nuclear shock front has spread after {@code seconds} of its blast: it slows as it spreads, to half its
     * first speed at the edge of the area, which it reaches at {@link #shockSeconds}. Never slower: what it brings down
     * goes like a wave, without a pause between the last buildings.
     */
    static float shockReach(float seconds, float radius) {
        return radius * ((float) Math.sqrt(1 + 3 * MathUtils.clamp(seconds / shockSeconds(radius), 0, 1)) - 1);
    }

    /** The blast seconds at which the front reaches {@code distance} from Ground Zero: {@link #shockReach} inverted. */
    static float shockArrival(float distance, float radius) {
        float share = MathUtils.clamp(distance / radius, 0, 1);
        return shockSeconds(radius) * share * (2 + share) / 3;
    }

    /** The blast seconds the front takes per world unit at {@code distance} from Ground Zero: {@link #shockArrival}'s slope. */
    static float shockSlowness(float distance, float radius) {
        return shockSeconds(radius) * 2 * (1 + MathUtils.clamp(distance / radius, 0, 1)) / (3 * radius);
    }

    /**
     * The live nuclear blasts' shock fronts, newest first, at most {@link #MAX_SHOCKS}, as terrain-shocks.glsl reads them:
     * ground zero's x and y, the blast area's radius in world units and the front's clock: the blast's seconds, scaled
     * so that every front crosses its area in {@link #SHOCK_SECONDS}. Returns how many.
     */
    int shocks(float[] out) {
        int count = 0;
        for (int index = blasts.size() - 1; index >= 0 && count < MAX_SHOCKS; index--) {
            Blast blast = blasts.get(index);
            if (blast.nuke == null || blast.seconds < 0) { continue; }
            float radius = nuclearRadius(blast.nuke);
            out[count * 4] = blast.ground.x;
            out[count * 4 + 1] = blast.ground.y;
            out[count * 4 + 2] = radius;
            out[count * 4 + 3] = blast.seconds * SHOCK_SECONDS / shockSeconds(radius);
            count++;
        }
        return count;
    }

    /** A nuclear blast area's radius in world units: where its damage, falling by the Degradation per hex, ends. */
    static float nuclearRadius(NukeStats nuke) {
        return (float) nuke.baseDamage / Math.max(1, nuke.degradation) * BoardGeometry.height();
    }

    int size() { return blasts.size(); }

    void clear() { blasts.clear(); }

    /** Queues the volumes of every live blast. */
    void emit(GpuExplosionEffects explosions) {
        for (Blast blast : blasts) {
            if (blast.nuke != null) {
                nuclear(explosions, blast.ground, nuclearRadius(blast.nuke), blast.seconds, blast.drawSeed, blast.orbital);
                continue;
            }
            var scatter = new Scatter(blast.pieces, blast.landings, blast.heading, blast.opening, blast.intensity);
            emit(explosions, blast.ground, blast.size, scatter, blast.style, blast.seconds, blast.drawSeed);
        }
    }

    /**
     * What a blast throws or drops, and where it all lands.
     *
     * @param pieces   the fragments, bomblets or mines
     * @param landings where each piece lands
     * @param heading  the shell's direction of travel across the ground, a unit vector
     */
    private record Scatter(List<Piece> pieces, List<Vector3> landings, Vector3 heading, @Nullable Vector3 opening,
          float intensity) { }

    /** The direction from {@code from} to {@code to} across the ground, or the default heading if they coincide. */
    private static Vector3 heading(Vector3 from, Vector3 to) {
        var heading = new Vector3(to.x - from.x, to.y - from.y, 0);
        return heading.isZero(.001f) ? DEFAULT_HEADING : heading.nor();
    }

    /**
     * Queues the volumes of one blast {@code seconds} after contact, on level ground centred on its target hex. The
     * result depends only on its arguments, so the shader preview and the review sheets can show any moment of any
     * blast. A {@link Style#CLUSTER} blast shows a Long Tom's cluster round ({@link #PREVIEW_CLUSTER}).
     */
    void emit(GpuExplosionEffects explosions, Vector3 ground, float size, int hexRadius, Style style, float seconds,
          int seed) {
        List<Piece> pieces = switch (style) {
            case MINES -> mines(seed, minesFor(PREVIEW_MINE_DENSITY));
            case CLUSTER -> bomblets(PREVIEW_CLUSTER, seed);
            case DUD, STAR_SHELL -> List.of();
            case FUEL_AIR -> fuelAirHexes(null, hexRadius);
            default -> debrisPieces(hexRadius, seed);
        };
        List<Vector3> landings = new ArrayList<>(pieces.size());
        for (Piece piece : pieces) {
            landings.add(new Vector3(ground.x + piece.offsetX(), ground.y + piece.offsetY(), ground.z));
        }
        emit(explosions, ground, size, new Scatter(pieces, landings, DEFAULT_HEADING, null, 1), style, seconds, seed);
    }

    private void emit(GpuExplosionEffects explosions, Vector3 ground, float size, Scatter scatter, Style style,
          float seconds, int seed) {
        if (style == Style.NUCLEAR) {
            nuclear(explosions, ground, nuclearRadius(AreaEffectHelper.getNukeStats(DAVY_CROCKETT_TYPE)), seconds, seed, false);
            return;
        }
        if (seconds < 0 || seconds >= SECONDS) { return; }
        if (style == Style.MINES) {
            minefield(explosions, ground, scatter, seconds, seed);
            return;
        }
        if (style == Style.CLUSTER) {
            cluster(explosions, ground, scatter, seconds, seed);
            return;
        }
        if (style == Style.DUD) {
            dud(explosions, ground, seconds, seed);
            return;
        }
        if (style == Style.FUEL_AIR) {
            fuelAir(explosions, ground, scatter, seconds, seed);
            return;
        }
        if (style == Style.STAR_SHELL) {
            starShell(explosions, burstPoint(ground, scatter.opening()), seconds, seed);
            return;
        }
        debris(explosions, ground, size, scatter.pieces(), scatter.landings(), seconds, seed);
        switch (style) {
            case CROWN -> crown(explosions, ground, size, seconds, seed);
            case MUSHROOM -> mushroom(explosions, ground, size, seconds, seed);
            case FIREBALL -> fireball(explosions, ground, size, seconds, seed);
            default -> { }
        }
        // A mushroom's cap is its smoke: it darkens where it is instead of leaving a second column under it.
        // The fireball's own smoke follows once it burns down; drawn earlier, the column cuts a dark streak through it.
        if (style == Style.CROWN) { smokeColumn(explosions, ground, size, seconds, seed, .4f); }
        if (style == Style.FIREBALL) { smokeColumn(explosions, ground, size, seconds, seed, 1.9f); }
    }

    /** Where an illumination round bursts: where its flight ended in the air, else straight over its target. */
    private static Vector3 burstPoint(Vector3 ground, @Nullable Vector3 opening) {
        return opening != null ? opening : new Vector3(ground).add(0, 0, BoardGeometry.height() * GpuStarShells.HANG_HEXES);
    }

    /**
     * Where each illumination round still bursting opened, by its target hex: the flare it hangs starts there and
     * glides to its place over its hex.
     */
    Map<Coords, Vector3> starShellOpenings() {
        Map<Coords, Vector3> openings = new HashMap<>();
        for (Blast blast : blasts) {
            if ((blast.style == Style.STAR_SHELL) && (blast.targetHex != null)) {
                openings.put(blast.targetHex, burstPoint(blast.ground, blast.opening));
            }
        }
        return openings;
    }

    /**
     * An illumination round bursting: a small sharp pop high over its target as the charge throws its flare out, and a
     * pale puff that drifts off. The flare itself hangs from GpuStarShells, for as long as the rules have it burn.
     */
    private void starShell(GpuExplosionEffects explosions, Vector3 burst, float seconds, int seed) {
        float hex = BoardGeometry.height();
        float flashAge = seconds / .25f;
        if (flashAge < 1) {
            explosions.add(burst, hex * (.06f + .08f * flashAge), flashAge, 1, fadeOut(flashAge, .3f), seed + 1,
                  BRIGHT_FIRE, Vector3.Zero);
        }
        float puffSeconds = seconds - .05f;
        float puffAge = puffSeconds / 1.6f;
        if (puffAge >= 0 && puffAge < 1) {
            float swell = 1 - (float) Math.exp(-puffSeconds * 2.5f);
            point.set(burst).add(0, 0, hex * .1f * swell);
            explosions.downwind(point, puffSeconds);
            explosions.add(point, hex * (.06f + .14f * swell), puffAge, 0, .4f * fadeOut(puffAge, .2f), seed + 2,
                  PALE_SMOKE, Vector3.Zero);
        }
    }

    /**
     * One piece for every hex a fuel-air round covers, the target hex and each ring out to {@code hexRadius}, at the
     * hex's centre. Each lights at ignition and leaves its hex charred.
     *
     * @param target the target hex, or {@code null} for a blast drawn without an attack, laid out around a fixed hex
     */
    static List<Piece> fuelAirHexes(@Nullable Coords target, int hexRadius) {
        Coords centre = target == null ? PREVIEW_HEX : target;
        Vector3 origin = BoardGeometry.center(centre, 0);
        List<Piece> pieces = new ArrayList<>();
        for (int ring = 0; ring <= hexRadius; ring++) {
            for (Coords hex : ring == 0 ? List.of(centre) : centre.allAtDistance(ring)) {
                Vector3 hexCentre = BoardGeometry.center(hex, 0);
                pieces.add(new Piece(hexCentre.x - origin.x, hexCentre.y - origin.y, ring, 0,
                      FUEL_AIR_IGNITION_SECONDS, true));
            }
        }
        return pieces;
    }

    /**
     * A fuel-air round, in four beats. Its fuel spreads as one pale, low haze over its whole radius; a moment after
     * landing the whole cloud lights at once, one sheet of fire over the radius, drawing in as it burns. The
     * fire rolls up into a tall, churning orange-and-black column flecked with burning metal, while a ring of dust
     * races out to the radius and burning chunks streak away. Heavy black soot is left to drift off on the wind.
     */
    private void fuelAir(GpuExplosionEffects explosions, Vector3 ground, Scatter scatter, float seconds, int seed) {
        float hex = BoardGeometry.height();
        float intensity = scatter.intensity();
        List<Piece> hexes = scatter.pieces();
        int radius = 0;
        for (Piece piece : hexes) { radius = Math.max(radius, piece.ring()); }
        float reach = .6f + .25f * radius;
        // One haze and one sheet of fire over the whole circular radius, not one of each per hex: an Arrow IV's 61
        // overlapping volumes cost a modest graphics chip far too much.
        float cloudRadius = hex * (radius + .6f);
        // The fuel spreads out from the target hex to the radius over the half second before it lights.
        float spread = MathUtils.clamp(seconds / FUEL_AIR_IGNITION_SECONDS, 0, 1);
        spread = spread * spread * (3 - 2 * spread);
        // It lingers a moment under the sheet of fire as it burns away: with one volume of each, the overlap is cheap.
        float hazeFade = MathUtils.clamp((FUEL_AIR_IGNITION_SECONDS + FUEL_HAZE_LINGER_SECONDS - seconds)
              / FUEL_HAZE_LINGER_SECONDS, 0, 1);
        if (seconds >= 0 && hazeFade > 0) {
            float hazeRadius = cloudRadius * (.12f + .88f * spread);
            point.set(ground).add(0, 0, hex * .1f);
            // Denser than one hex's haze was: the many overlapping hazes it replaces built up to this.
            explosions.addFlat(point, hazeRadius, .1f, 0, FUEL_HAZE_OPACITY * hazeFade, seed + 13, FUEL_HAZE,
                  flatness(hex * .3f, hazeRadius));
        }
        float sheet = (seconds - FUEL_AIR_IGNITION_SECONDS) / .75f;
        if (sheet >= 0 && sheet < 1) {
            // The sheet draws in and up as it burns, rolling into the column over the target hex.
            float sheetRadius = cloudRadius * intensity * (1 - .45f * sheet);
            point.set(ground).add(0, 0, hex * (.12f + .45f * sheet));
            explosions.addFlat(point, sheetRadius, sheet, 1, fadeOut(sheet, .45f), seed + 17, FUEL_FIRE,
                  flatness(hex * (.35f + .5f * sheet), sheetRadius));
        }
        float columnSeconds = seconds - FUEL_AIR_IGNITION_SECONDS;
        float columnAge = columnSeconds / 2.4f;
        if (columnAge >= 0 && columnAge < 1) {
            // It lights with the sheet and grows as the sheet rolls into it, so the two read as one fire.
            float rise = 1 - (float) Math.exp(-columnSeconds * 1.8f);
            float fire = MathUtils.clamp(1.15f - columnAge * 1.5f, 0, 1);
            for (int level = 0; level < 3; level++) {
                point.set(ground).add(0, 0, hex * (.3f + .6f * level) * rise * intensity * reach);
                explosions.downwind(point, columnSeconds * .5f);
                axis.set(Vector3.Z).scl(.35f);
                explosions.add(point, hex * (.85f - .12f * level) * (.45f + .6f * rise) * intensity * reach,
                      columnAge, fire, fadeOut(columnAge, .35f), seed + 300 + level * 29, FUEL_COLUMN, axis);
            }
        }
        float ringAge = (seconds - FUEL_AIR_IGNITION_SECONDS) / .9f;
        if (ringAge >= 0 && ringAge < 1) {
            float out = hex * (radius + .6f) * (1 - (1 - ringAge) * (1 - ringAge));
            for (int puff = 0; puff < 12; puff++) {
                float bearing = puff * MathUtils.PI2 / 12 + random(seed, puff + 700) * .3f;
                point.set(ground).add(MathUtils.cos(bearing) * out, MathUtils.sin(bearing) * out, hex * .05f);
                explosions.add(point, hex * (.05f + .07f * ringAge), ringAge, 0, .5f * fadeOut(ringAge, .2f),
                      seed + 400 + puff, EARTH, Vector3.Zero);
            }
        }
        for (int chunk = 0; chunk < FUEL_AIR_CHUNKS; chunk++) {
            float launch = FUEL_AIR_IGNITION_SECONDS + .02f * (chunk % 4);
            float travel = (seconds - launch) / (.8f + .3f * random(seed, chunk + 720));
            if (travel <= 0 || travel >= 1) {
                continue;
            }
            float bearing = chunk * 2.399963f + random(seed, chunk + 740);
            float out = hex * (radius + .3f) * (.6f + .4f * random(seed, chunk + 760)) * travel;
            float up = hex * (.5f + .4f * random(seed, chunk + 780)) * 4 * travel * (1 - travel);
            point.set(ground).add(MathUtils.cos(bearing) * out, MathUtils.sin(bearing) * out, up + hex * .1f);
            axis.set(MathUtils.cos(bearing), MathUtils.sin(bearing), (1 - 2 * travel) * 1.2f).nor().scl(2.5f);
            explosions.add(point, hex * .022f, .5f, 1, .9f * fadeOut(travel, .4f), seed + 500 + chunk, FUEL_CHUNK,
                  axis);
        }
        float sootSeconds = seconds - 1.4f;
        float sootAge = sootSeconds / (SECONDS - 1.4f);
        if (sootAge >= 0 && sootAge < 1) {
            float swell = 1 - (float) Math.exp(-sootSeconds * .9f);
            for (int level = 0; level < 3; level++) {
                point.set(ground).add(0, 0, hex * (1 + .7f * level) * (.7f + .5f * swell) * intensity * reach);
                explosions.downwind(point, sootSeconds);
                explosions.add(point, hex * (.5f + .4f * swell) * (1 - .1f * level) * intensity * reach, sootAge, 0,
                      .95f * fadeOut(sootAge, .55f), seed + 600 + level * 31, FUEL_SOOT, Vector3.Zero);
            }
        }
    }

    /** How flat a volume of {@code radius} must be to stand {@code height} tall: its height as a share of its width. */
    private static float flatness(float height, float radius) {
        return MathUtils.clamp(height / radius, .1f, 1);
    }

    /**
     * A round that came down without going off, a homing round with no TAG to follow: a short burst of dirt where it
     * struck, a few clods thrown up and falling back, and a low dust cloud that settles. No flash, no smoke column.
     */
    private void dud(GpuExplosionEffects explosions, Vector3 ground, float seconds, int seed) {
        float hex = BoardGeometry.height();
        for (int clod = 0; clod < DUD_CLODS; clod++) {
            float age = (seconds - clod * .03f) / 1.4f;
            if ((age < 0) || (age >= 1)) {
                continue;
            }
            float bearing = clod * 2.399963f + random(seed, clod + 600);
            float out = hex * .14f * (.4f + .6f * random(seed, clod + 620)) * (1 - (1 - age) * (1 - age));
            // Up and back down, a quick arc.
            float rise = hex * (.16f + .12f * random(seed, clod + 640)) * 4 * age * (1 - age);
            point.set(ground).add(MathUtils.cos(bearing) * out, MathUtils.sin(bearing) * out, rise + hex * .02f);
            explosions.add(point, hex * (.03f + .04f * age), age, 0, .75f * fadeOut(age, .3f), seed + clod * 37,
                  EARTH, Vector3.Zero);
        }
        float dustSeconds = seconds - .05f;
        float dustAge = dustSeconds / 2.2f;
        if ((dustAge >= 0) && (dustAge < 1)) {
            float swell = 1 - (float) Math.exp(-dustSeconds * 2.5f);
            point.set(ground).add(0, 0, hex * .04f);
            explosions.downwind(point, dustSeconds);
            explosions.add(point, hex * (.06f + .14f * swell), dustAge, 0, .45f * fadeOut(dustAge, .2f), seed + 1,
                  EARTH, Vector3.Zero);
        }
    }

    /**
     * A minefield round: no blast, only the small charge that pushes the mines out of the back of the shell in the
     * air above the hex, a brief pop and a thin puff that clears within a second. The mines leave one after another
     * as the shell carries on, a short stream along its line of flight, then fan out and drift down into that hex
     * alone, each settling with a small puff of dust and lying there as a small dark shape until the blast is over.
     * The minefield marker shows the field after that, to the players who know of it.
     */
    private void minefield(GpuExplosionEffects explosions, Vector3 ground, Scatter scatter, float seconds, int seed) {
        float hex = BoardGeometry.height();
        Vector3 heading = scatter.heading();
        if (scatter.opening() != null) {
            // The round's flight ended where it came down to the dispenser's height: it opens right there.
            burst.set(scatter.opening());
        } else {
            // The shell opens a little short of the hex's centre, so its stream of mines passes over the middle.
            burst.set(ground).mulAdd(heading, -hex * DISPENSER_LEAD).add(0, 0, hex * DISPENSER_HEXES);
        }
        float popAge = seconds / .15f;
        if (popAge < 1) {
            explosions.add(burst, hex * (.07f + .05f * popAge), popAge, .3f, .8f * fadeOut(popAge, .3f), seed + 1,
                  PALE_SMOKE, Vector3.Zero);
        }
        float puffSeconds = seconds - .03f;
        float puffAge = puffSeconds / 1.2f;
        if (puffAge >= 0 && puffAge < 1) {
            float swell = 1 - (float) Math.exp(-puffSeconds * 3);
            point.set(burst).mulAdd(heading, hex * .1f * swell);
            explosions.downwind(point, puffSeconds);
            explosions.add(point, hex * (.07f + .12f * swell), puffAge, 0, .3f * fadeOut(puffAge, .1f), seed + 2,
                  PALE_SMOKE, Vector3.Zero);
        }
        List<Piece> pieces = scatter.pieces();
        for (int index = 0; index < pieces.size(); index++) {
            Piece piece = pieces.get(index);
            Vector3 landing = scatter.landings().get(index);
            float mineSize = hex * MINE_SIZE;
            float travel = (seconds - piece.launch()) / piece.flight();
            if (travel > 0 && travel < 1) {
                // Each mine leaves where the shell has got to by its release, so together they form a stream along
                // the line of flight; then it falls, slowing as it nears the ground, pushed a little downwind.
                point.set(burst).mulAdd(heading, hex * DISPENSER_SPEED * piece.launch());
                float eased = 1 - (1 - travel) * (1 - travel);
                point.lerp(landing, eased);
                explosions.downwind(point, .25f * travel * (1 - travel));
                explosions.add(point, mineSize, .5f, 0, 1, seed + index * 41, MINE, Vector3.Zero);
            } else if (travel >= 1) {
                point.set(landing).add(0, 0, mineSize * .6f);
                explosions.add(point, mineSize, .5f, 0, 1, seed + index * 41, MINE, Vector3.Zero);
            }
            float dustAge = (seconds - piece.landing()) / 1.1f;
            if (dustAge >= 0 && dustAge < 1) {
                point.set(landing).add(0, 0, hex * .03f);
                explosions.add(point, hex * (.04f + .05f * dustAge), dustAge, 0, .45f * fadeOut(dustAge, .2f),
                      seed + index * 43, EARTH, Vector3.Zero);
            }
        }
    }

    /**
     * A cluster round: the casing bursts in a flash and a puff of smoke above the target, its bomblets fall as dark
     * streaks across the target hex and the ring around it, and each pops in a small sharp flash where it lands,
     * leaving a puff of smoke. The puffs drift together into a thin haze; there is no single fireball.
     */
    private void cluster(GpuExplosionEffects explosions, Vector3 ground, Scatter scatter, float seconds, int seed) {
        float hex = BoardGeometry.height();
        List<Piece> pieces = scatter.pieces();
        List<Vector3> landings = scatter.landings();
        // Where the round's flight ended at the burst height, else straight above its target.
        if (scatter.opening() != null) {
            burst.set(scatter.opening());
        } else {
            burst.set(ground).add(0, 0, hex * AIR_BURST_HEXES);
        }
        float flashAge = seconds / .35f;
        if (flashAge < 1) {
            explosions.add(burst, hex * (.18f + .3f * flashAge), flashAge, 1, fadeOut(flashAge, .3f), seed + 1,
                  BRIGHT_FIRE, Vector3.Zero);
        }
        // Smoke follows its flash rather than sharing its centre and moment, which would cover the flash's bright core.
        // The casing's smoke swells fast and thins as it spreads, and the wind carries it off.
        float smokeSeconds = seconds - .2f;
        float casingSmokeAge = smokeSeconds / 2.4f;
        if (casingSmokeAge >= 0 && casingSmokeAge < 1) {
            float swell = 1 - (float) Math.exp(-smokeSeconds * 1.8f);
            point.set(burst).add(0, 0, hex * .12f * casingSmokeAge);
            explosions.downwind(point, smokeSeconds);
            explosions.add(point, hex * (.15f + .6f * swell), casingSmokeAge, .15f,
                  .85f * fadeOut(casingSmokeAge, .15f), seed + 2, BURST_SMOKE, Vector3.Zero);
        }
        for (int index = 0; index < pieces.size(); index++) {
            Piece piece = pieces.get(index);
            Vector3 landing = landings.get(index);
            float travel = (seconds - piece.launch()) / piece.flight();
            if (travel > 0 && travel < 1) {
                // Slow out of the casing, fast into the ground.
                point.set(burst).lerp(landing, travel * travel);
                axis.set(landing).sub(burst).nor().scl(2.2f);
                explosions.add(point, hex * .04f, .5f, 0, .9f, seed + index * 41, DEBRIS, axis);
            }
            float popAge = (seconds - piece.landing()) / .7f;
            if (popAge >= 0 && popAge < 1) {
                point.set(landing).add(0, 0, hex * .05f);
                float popSize = hex * (.05f + .09f * (1 - (float) Math.exp(-popAge * 8)));
                explosions.add(point, popSize, popAge, 1, fadeOut(popAge, .4f), seed + index * 43, BRIGHT_FIRE,
                      Vector3.Zero);
            }
            float smokeAge = (seconds - piece.landing() - .4f) / 2.4f;
            if (smokeAge >= 0 && smokeAge < 1) {
                point.set(landing).add(0, 0, hex * (.06f + .1f * smokeAge));
                explosions.downwind(point, seconds - piece.landing() - .4f);
                explosions.add(point, hex * (.08f + .1f * smokeAge), smokeAge, .1f, .6f * fadeOut(smokeAge, .35f),
                      seed + index * 47, BURST_SMOKE, Vector3.Zero);
            }
        }
    }

    /** Spikes of fire and earth thrown up and out in a cone around a low core, with a dust skirt racing outward. */
    private void crown(GpuExplosionEffects explosions, Vector3 ground, float size, float seconds, int seed) {
        core(explosions, ground, size, seconds, seed, 1.1f);
        int spikes = 11;
        float life = 2.6f;
        float age = seconds / life;
        if (age < 1) {
            float thrown = 1 - (float) Math.exp(-age * 5);
            for (int spike = 0; spike < spikes; spike++) {
                float bearing = spike * 2.399963f + random(seed, spike) * .6f;
                float tilt = MathUtils.lerp(.12f, .95f, random(seed, spike + 20));
                float reach = size * MathUtils.lerp(.7f, 1.3f, random(seed, spike + 40));
                axis.set(MathUtils.sin(tilt) * MathUtils.cos(bearing), MathUtils.sin(tilt) * MathUtils.sin(bearing),
                      MathUtils.cos(tilt));
                point.set(ground).mulAdd(axis, size * .12f + reach * thrown * .6f);
                float radius = size * MathUtils.lerp(.24f, .32f, random(seed, spike + 60)) * (.45f + .55f * thrown);
                axis.scl(1.6f * (1 - age * .45f));
                // Every other spike is mostly earth and smoke, so the crown is fire mixed through dark smoke.
                float fire = spike % 2 == 0 ? 1 : .55f;
                explosions.add(point, radius, age, fire, fadeOut(age, .7f), seed + spike * 13, FIRE, axis);
            }
        }
        skirt(explosions, ground, size, seconds, seed);
    }

    /** A fireball that rises on a thin smoke stem and rolls into a dark cap, with fire still burning at its foot. */
    private void mushroom(GpuExplosionEffects explosions, Vector3 ground, float size, float seconds, int seed) {
        float age = seconds / SECONDS;
        float rise = 1 - (float) Math.exp(-seconds * .75f);
        float capHeight = size * (.3f + 1.5f * rise);
        float capRadius = size * (.34f + .3f * rise);
        float alpha = fadeOut(age, .6f);
        point.set(ground).add(0, 0, capHeight);
        explosions.add(point, capRadius, age, 1, alpha, seed, CAP, Vector3.Zero);
        // The cap's billows: smaller lobes around and above its middle make the cauliflower top.
        for (int lobe = 0; lobe < 5; lobe++) {
            float bearing = lobe * MathUtils.PI2 / 5 + random(seed, lobe + 80);
            float out = lobe == 4 ? 0 : capRadius * .6f;
            float lift = lobe == 4 ? .5f : .15f + .3f * random(seed, lobe + 90);
            point.set(ground).add(MathUtils.cos(bearing) * out, MathUtils.sin(bearing) * out, capHeight + capRadius * lift);
            explosions.add(point, capRadius * .62f, age, 1, alpha, seed + lobe * 17, CAP, Vector3.Zero);
        }
        // The stem follows the cap up once it has left the ground: overlapping puffs, so it reads as one column.
        float stemTop = capHeight - capRadius * .6f;
        float stemRadius = size * .12f;
        int puffs = Math.min(10, (int) Math.ceil(stemTop / (stemRadius * 1.2f)));
        for (int puff = 0; puff < puffs; puff++) {
            point.set(ground).add(0, 0, stemTop * (puff + .5f) / puffs);
            float flare = 1 + .5f * (1 - (puff + .5f) / puffs);
            explosions.add(point, stemRadius * flare, age, .35f, alpha * .85f, seed + puff * 29, SMOKE, Vector3.Zero);
        }
        float footAge = seconds / 2.6f;
        if (footAge < 1) {
            point.set(ground).add(0, 0, size * .08f);
            explosions.add(point, size * .24f, footAge, 1, fadeOut(footAge, .6f), seed + 7, BRIGHT_FIRE, Vector3.Zero);
        }
        skirt(explosions, ground, size * .75f, seconds, seed);
    }

    /**
     * A nuclear blast, every size of it scaled to its blast area's {@code radius}: a blinding fireball that swells and
     * rises into the mushroom (the artillery's own, its clock slowed to the long rise), the shock front racing out over
     * the whole blast area in its first seconds with a ring of thrown dust, a condensation ring hanging around the stem
     * as the front passes, and the base surge rolling out beneath. The fireball lights the ground as it burns
     * (GpuExplosionEffects.lights).
     */
    private void nuclear(GpuExplosionEffects explosions, Vector3 ground, float radius, float seconds, int seed,
          boolean orbital) {
        if (seconds < 0 || seconds >= NUCLEAR_SECONDS) { return; }
        float lance = seconds / .5f;
        if (orbital && lance < 1) {
            // An orbital bombardment comes down from the sky as a lance of fire, gone into its fireball within half a second.
            for (int puff = 0; puff < 6; puff++) {
                point.set(ground).add(0, 0, radius * (.08f + .22f * puff) * (1 - lance * .8f));
                explosions.add(point, radius * (.05f - .005f * puff), lance, 1, fadeOut(lance, .4f), seed + 61 + puff,
                      BRIGHT_FIRE, Vector3.Zero);
            }
        }
        float flash = seconds / 1.8f;
        if (flash < 1) {
            float swell = 1 - (float) Math.exp(-seconds * 5);
            point.set(ground).add(0, 0, radius * (.06f + .1f * flash));
            explosions.add(point, radius * (.05f + .13f * swell), flash * .5f, 1, fadeOut(flash, .5f), seed,
                  BRIGHT_FIRE, Vector3.Zero);
            // Its white-hot core, the blinding first instant.
            explosions.add(point, radius * (.03f + .07f * swell), flash * .2f, 1, fadeOut(flash, .25f), seed + 3,
                  BRIGHT_FIRE, Vector3.Zero);
        }
        if (!orbital) { mushroom(explosions, ground, radius * .42f, seconds * SECONDS / NUCLEAR_SECONDS, seed); }
        float front = seconds / shockSeconds(radius);
        if (front < 1) {
            // The front slows as it spreads; its dust thins toward the edge of the blast area.
            float reach = shockReach(seconds, radius);
            int puffs = 96;
            for (int puff = 0; puff < puffs; puff++) {
                float bearing = (puff + random(seed, puff + 300)) * MathUtils.PI2 / puffs;
                // The dust it throws up rolls into a wall that rises behind it.
                point.set(ground).add(MathUtils.cos(bearing) * reach, MathUtils.sin(bearing) * reach,
                      radius * .02f * front);
                explosions.addFlat(point, radius * (.045f + .05f * front), front, 0, .5f * fadeOut(front, .35f),
                      seed + puff * 7, EARTH, .5f);
            }
        }
        float ring = (seconds - .3f) / 2.6f;
        if (!orbital && ring > 0 && ring < 1) {
            point.set(ground).add(0, 0, radius * .3f);
            explosions.addFlat(point, radius * (.15f + .2f * ring), ring, 0, .5f * MathUtils.sin(ring * MathUtils.PI),
                  seed + 41, FUEL_HAZE, .1f);
        }
    }

    /** A bright, billowing fireball that lingers before going dark, over a base of black smoke. */
    private void fireball(GpuExplosionEffects explosions, Vector3 ground, float size, float seconds, int seed) {
        float life = 3.4f;
        float age = seconds / life;
        if (age >= 1) { return; }
        float swell = 1 - (float) Math.exp(-age * 6);
        float alpha = fadeOut(age, .7f);
        for (int lobe = 0; lobe < 6; lobe++) {
            float bearing = lobe * 2.399963f + random(seed, lobe + 100);
            float out = lobe == 0 ? 0 : size * .36f * swell;
            float lift = lobe == 0 ? .1f : .3f * random(seed, lobe + 110);
            point.set(ground).add(MathUtils.cos(bearing) * out, MathUtils.sin(bearing) * out,
                  size * (.6f + .35f * age + lift));
            float radius = size * (lobe == 0 ? .62f : .44f) * (.4f + .6f * swell);
            explosions.add(point, radius, age, 1, alpha, seed + lobe * 19, BRIGHT_FIRE, Vector3.Zero);
        }
        for (int puff = 0; puff < 6; puff++) {
            float bearing = puff * MathUtils.PI2 / 6 + random(seed, puff + 120);
            float out = size * (.3f + .25f * swell);
            point.set(ground).add(MathUtils.cos(bearing) * out, MathUtils.sin(bearing) * out, size * .18f);
            explosions.add(point, size * .3f * (.5f + .5f * swell), age, .25f, alpha * .9f, seed + puff * 23, SMOKE,
                  Vector3.Zero);
        }
        skirt(explosions, ground, size * .8f, seconds, seed);
    }

    /**
     * Shell fragments: too fast to follow, so in flight each is only a faint streak on a nearly straight line, and
     * they strike the damaged hexes as a quick ripple outward, inner ring first, within about a third of a second.
     * Where one strikes, it kicks a thin spurt of dirt up off the ground that falls back and fades in half a second;
     * the fragment that marks its hex kicks a slightly larger one.
     */
    private void debris(GpuExplosionEffects explosions, Vector3 ground, float size, List<Piece> pieces,
          List<Vector3> landings, float seconds, int seed) {
        float hex = BoardGeometry.height();
        for (int index = 0; index < pieces.size(); index++) {
            Piece piece = pieces.get(index);
            Vector3 landing = landings.get(index);
            float travel = (seconds - piece.launch()) / piece.flight();
            if (travel > 0 && travel < 1) {
                // Fragments leave the burst at about the height of its core and fly almost flat.
                point.set(ground).lerp(landing, travel).add(0, 0, size * .25f * (1 - travel));
                axis.set(landing).sub(ground).nor().scl(3);
                explosions.add(point, hex * .012f, .5f, .2f, .6f, seed + index * 41, DEBRIS, axis);
            }
            float kickAge = (seconds - piece.landing()) / .55f;
            if (kickAge >= 0 && kickAge < 1) {
                float scale = piece.marksGround() ? 1.4f : 1;
                float rise = 1 - (float) Math.exp(-kickAge * 6);
                point.set(landing).add(0, 0, hex * (.02f + .1f * rise) * scale);
                // Drawn up out of the ground, so it reads as a sharp spurt rather than a round puff.
                axis.set(Vector3.Z).scl(2.2f * (1 - .5f * kickAge));
                float spurt = hex * (.022f + .02f * kickAge) * scale;
                explosions.add(point, spurt, kickAge, 0, .75f * fadeOut(kickAge, .25f), seed + index * 43, EARTH, axis);
            }
        }
    }

    /**
     * Hands over a scorch mark for each piece of debris that has just landed in a ring around the target hex, sized for
     * the damage that ring takes, so the burned ground shows how far the shell reached. Debris landing in the target
     * hex leaves nothing more, as the shell's own crater already marks it. Each mark is handed over once.
     */
    void groundMarks(Consumer<GpuGroundDamage.Impact> receiver) {
        for (Blast blast : blasts) {
            if (!leavesRingMarks(blast)) { continue; }
            for (int index = 0; index < blast.pieces.size(); index++) {
                Piece piece = blast.pieces.get(index);
                boolean handled = blast.landed.get(index);
                if (handled || blast.seconds < piece.landing()) { continue; }
                blast.landed.set(index);
                // One fragment per ring hex marks it, and a high-explosive shell's own crater marks its target hex.
                // A cluster round has no crater, so every bomblet marks, the target hex's included.
                if (!piece.marksGround() || !blast.onTerrain[index]) { continue; }
                blast.marks++;
                double damage = blast.style == Style.FUEL_AIR
                      ? fuelAirMarkDamage(blast.area, piece.ring(), blast.intensity)
                      : markDamage(blast.area, piece.ring());
                ResolvedAttack.Shot markShot = blast.profile.withDamagePerHit(damage);
                receiver.accept(new GpuGroundDamage.Impact(blast.attack, "artillery-debris-" + index,
                      blast.ground.cpy(), blast.landings.get(index).cpy(), "bomb", markShot));
            }
            boolean allLanded = blast.landed.cardinality() == blast.pieces.size();
            if (allLanded && !blast.marksReported) {
                blast.marksReported = true;
                LOGGER.debug("[ArtilleryBlast] {} debris marks over R{} around {}", blast.marks,
                      blast.area.hexRadius(), blast.ground);
            }
        }
    }

    /**
     * The damage a fuel-air round does in a ring, which sets how dark its charring is there: the fuel-air table's,
     * not the usual falloff (an Arrow IV's 20, 10 and 5), halved in thin air.
     */
    static double fuelAirMarkDamage(AreaEffect area, int ring, float intensity) {
        int damage = AreaEffectHelper.fuelAirRingDamage(area.hexRadius(), ring);
        return intensity < 1 ? Math.ceil(damage / 2.0) : damage;
    }

    /**
     * The damage one mark is sized for: a ring's damage for a high-explosive shell's debris, and a bomblet's share of
     * its hex's damage for a cluster round, so a cluster round freckles the ground with small marks.
     */
    static double markDamage(AreaEffect area, int ring) {
        int damage = area.ringDamage(ring);
        return area.cluster() ? (double) damage / bombletsPerHex(damage) : damage;
    }

    /** Whether the blast has rings around its target to mark, and the attack and shot it needs to mark them. */
    private static boolean leavesRingMarks(Blast blast) {
        if (blast.area.hexRadius() == 0 || blast.profile == null) { return false; }
        return blast.attack != null && blast.attack.landscape != null;
    }

    /** Where a vertical line through {@code x}, {@code y} meets the installed terrain, searching well above and below. */
    static @Nullable Vector3 terrain(Function<Ray, BoardGeometry.Hit> landscape, float x, float y,
          float near) {
        float drop = BoardGeometry.height() * 8;
        var ray = new Ray(new Vector3(x, y, near + drop / 2), new Vector3(0, 0, -1));
        BoardGeometry.Hit hit = landscape.apply(ray);
        // A hit's distance is squared, as every landscape query reports it.
        return hit == null ? null : ray.getEndPoint(new Vector3(), (float) Math.sqrt(hit.distance()));
    }

    /** The density of each minefield the markers show, by hex: the minefields the local player knows of. */
    static Map<Coords, Integer> minefields(List<BoardMarker> markers) {
        Map<Coords, Integer> minefields = new LinkedHashMap<>();
        for (BoardMarker marker : markers) {
            if (marker.kind() == BoardMarker.Kind.MINEFIELD) {
                minefields.put(marker.coords(), marker.density());
            }
        }
        return minefields;
    }

    /**
     * Queues the mines lying in each hex with a minefield the local player knows of, for as long as it lasts: the
     * client knows only the minefields its player may, and a hex leaves {@code mined} once its field is detonated or
     * cleared. A hex whose mines are still being dropped is left to its drop, which lays them in the same places. As
     * detonations thin a field its density falls, and so does the number of mines drawn there.
     *
     * @param minefields the density of each known minefield, by hex
     * @param landscape finds the installed terrain under a mine
     */
    void restingMines(GpuExplosionEffects explosions, Map<Coords, Integer> minefields,
          Function<Ray, BoardGeometry.Hit> landscape) {
        restingMines.keySet().retainAll(minefields.keySet());
        float mineSize = BoardGeometry.height() * MINE_SIZE;
        for (Map.Entry<Coords, Integer> minefield : minefields.entrySet()) {
            Coords hex = minefield.getKey();
            if (beingDropped(hex)) {
                continue;
            }
            int count = minesFor(minefield.getValue());
            List<Vector3> mines = restingMines.get(hex);
            if ((mines == null) || (mines.size() != count)) {
                mines = placeMines(hex, count, landscape);
                if (mines.isEmpty()) {
                    // The terrain under the hex is not installed yet; a later frame places them.
                    continue;
                }
                restingMines.put(hex, mines);
                LOGGER.debug("[ArtilleryBlast] {} mines lie in the minefield at {}", mines.size(), hex);
            }
            int seed = mineSeed(hex);
            for (int index = 0; index < mines.size(); index++) {
                point.set(mines.get(index)).add(0, 0, mineSize * .6f);
                explosions.add(point, mineSize, .5f, 0, 1, seed + index * 41, MINE, Vector3.Zero);
            }
        }
    }

    /** Whether a minefield round's drop into the hex is still being drawn. */
    private boolean beingDropped(Coords hex) {
        for (Blast blast : blasts) {
            if ((blast.style == Style.MINES) && hex.equals(blast.targetHex)) {
                return true;
            }
        }
        return false;
    }

    /** Where the mines of the hex lie: where its drop lays them, on the installed terrain. */
    static List<Vector3> placeMines(Coords hex, int count, Function<Ray, BoardGeometry.Hit> landscape) {
        Vector3 centre = BoardGeometry.center(hex, 0);
        List<Vector3> placed = new ArrayList<>();
        for (Piece mine : mines(mineSeed(hex), count)) {
            Vector3 ground = terrain(landscape, centre.x + mine.offsetX(), centre.y + mine.offsetY(), centre.z);
            if (ground != null) {
                placed.add(ground);
            }
        }
        return placed;
    }

    /** The seed of a hex's mines, the same for its drop and for the mines lying there afterwards. */
    static int mineSeed(Coords hex) {
        return hex.getX() * 7919 + hex.getY() * 104729 + 17;
    }

    /** The first instant: a hot, low core that flashes and swells before the shape of the blast takes over. */
    private void core(GpuExplosionEffects explosions, Vector3 ground, float size, float seconds, int seed, float life) {
        float age = seconds / life;
        if (age >= 1) { return; }
        float swell = .35f + .65f * (1 - (float) Math.exp(-age * 9));
        point.set(ground).add(0, 0, size * .25f);
        explosions.add(point, size * .48f * swell, age, 1, fadeOut(age, .5f), seed + 3, BRIGHT_FIRE, Vector3.Zero);
    }

    /** Earth thrown sideways along the ground: a low ring of dust that races out and settles. */
    private void skirt(GpuExplosionEffects explosions, Vector3 ground, float size, float seconds, int seed) {
        float life = 2.6f;
        float age = (seconds - .05f) / life;
        if (age < 0 || age >= 1) { return; }
        float spread = 1 - (float) Math.exp(-age * 4);
        int puffs = 12;
        for (int puff = 0; puff < puffs; puff++) {
            float bearing = puff * MathUtils.PI2 / puffs + random(seed, puff + 140) * .3f;
            // Each puff travels and swells by its own amount, so the edge is ragged rather than a neat ring.
            float out = size * (.35f + 1.1f * spread * MathUtils.lerp(.6f, 1.2f, random(seed, puff + 150)));
            float radius = size * .2f * (.7f + .9f * age) * MathUtils.lerp(.7f, 1.3f, random(seed, puff + 170));
            point.set(ground).add(MathUtils.cos(bearing) * out, MathUtils.sin(bearing) * out, size * .07f);
            // Drawn out around the ring, so neighbouring puffs merge into one sheet of dust sliding outward.
            axis.set(-MathUtils.sin(bearing), MathUtils.cos(bearing), 0).scl(1.1f);
            explosions.add(point, radius, age, 0, .4f * fadeOut(age, .35f), seed + puff * 31, EARTH, axis);
        }
    }

    /**
     * The dark mass the blast leaves behind, appearing {@code start} seconds after contact: it rises, spreads and
     * drifts with the wind until it clears.
     */
    private void smokeColumn(GpuExplosionEffects explosions, Vector3 ground, float size, float seconds, int seed,
          float start) {
        float age = (seconds - start) / (SECONDS - start);
        if (age < 0 || age >= 1) { return; }
        float fadeIn = MathUtils.clamp((seconds - start) / .6f, 0, 1);
        float alpha = fadeIn * fadeOut(age, .5f);
        for (int puff = 0; puff < 4; puff++) {
            float bearing = puff * 2.1f + random(seed, puff + 160);
            point.set(ground).add(MathUtils.cos(bearing) * size * .18f, MathUtils.sin(bearing) * size * .18f,
                  size * (.3f + puff * .26f + age * .9f));
            // Only faint embers: a hotter column reads as a second explosion bursting upward out of the first.
            explosions.add(point, size * (.42f + .45f * age) * (1 - puff * .1f), age, .12f, alpha, seed + puff * 37,
                  SMOKE, Vector3.Zero);
        }
    }

    /** 1 until {@code from}, then easing smoothly to 0 at the end of the layer's life. */
    static float fadeOut(float age, float from) {
        float fade = MathUtils.clamp((age - from) / (1 - from), 0, 1);
        return 1 - fade * fade * (3 - 2 * fade);
    }

    /** A stable value in {@code [0, 1)} for one detail of one blast, so a paused or replayed blast never flickers. */
    static float random(int seed, int detail) {
        int hash = seed * 0x9E3779B1 + detail * 0x85EBCA6B;
        hash ^= hash >>> 15;
        hash *= 0x2C1B3C6D;
        hash ^= hash >>> 12;
        return (hash >>> 8) / (float) (1 << 24);
    }
}
