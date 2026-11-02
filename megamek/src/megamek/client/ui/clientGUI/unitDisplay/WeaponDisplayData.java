/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.unitDisplay;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import jakarta.annotation.Nullable;
import megamek.client.Client;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.tooltip.UnitToolTip;
import megamek.client.ui.util.UIUtil;
import megamek.common.Hex;
import megamek.common.RangeType;
import megamek.common.battleArmor.BattleArmor;
import megamek.common.board.Coords;
import megamek.common.compute.ArtilleryRange;
import megamek.common.compute.Compute;
import megamek.common.enums.GamePhase;
import megamek.common.enums.WeaponSortOrder;
import megamek.common.equipment.AmmoMounted;
import megamek.common.equipment.AmmoType;
import megamek.common.equipment.AmmoType.AmmoTypeEnum;
import megamek.common.equipment.AmmoType.Munitions;
import megamek.common.equipment.HandheldWeapon;
import megamek.common.equipment.Mounted;
import megamek.common.equipment.TrainAmmoSharing;
import megamek.common.equipment.WeaponMounted;
import megamek.common.equipment.WeaponType;
import megamek.common.game.Game;
import megamek.common.interfaces.ILocationExposureStatus;
import megamek.common.options.OptionsConstants;
import megamek.common.units.*;
import megamek.common.weapons.bayWeapons.BayWeapon;
import megamek.common.weapons.capitalWeapons.CapitalMissileWeapon;
import megamek.common.weapons.gaussRifles.HAGWeapon;
import megamek.common.weapons.handlers.AreaEffectHelper;
import megamek.common.weapons.handlers.DamageFalloff;
import megamek.common.weapons.infantry.InfantryWeapon;
import megamek.logging.MMLogger;

/** Weapon statistics, ammunition choices and weapon ordering shared by unit views. */
public final class WeaponDisplayData {
    private WeaponDisplayData() { }

    private static final MMLogger logger = MMLogger.create(WeaponDisplayData.class);

    /**
     * The heat the unit builds up this turn, as the weapon display shows it: the heat left from last round, engine
     * hits, external heat, movement, fire and terrain, temperature, active stealth and similar systems, the weapons
     * fired this round (by arc or bay on large craft) and cooling, never below 0; the game is the client's, null
     * without a client.
     *
     * @param value        the heat buildup
     * @param overCapacity by how much it exceeds the unit's heat capacity (with water), 0 or less when it does not
     * @param text         the display text: the buildup (marked "*" when over capacity), the capacity, the heat over
     *                     it, and the combat computer and extreme temperature marks
     */
    public record HeatBuildup(int value, int overCapacity, String text) { }

    /** The unit's heat buildup this turn ({@link HeatBuildup}); the Unit Display and the GPU unit record use it. */
    public static HeatBuildup heatBuildup(@Nullable Game game, Entity en) {
        Entity entity = en;
        // Check Game Options for max external heat
        int max_ext_heat = game != null ?
              game.getOptions().intOption(OptionsConstants.ADVANCED_COMBAT_MAX_EXTERNAL_HEAT)
              :
              15;
        if (max_ext_heat < 0) {
            max_ext_heat = 15; // Standard value specified in TW p.159
        }

        int currentHeatBuildup = (en.heat // heat from last round
              + en.getEngineCritHeat() // heat engine crits will add
              + Math.min(max_ext_heat, en.heatFromExternal) // heat from external sources
              + en.heatBuildup) // heat we're building up this round
              - Math.min(9, en.coolFromExternal); // cooling from external

        // sources
        if (en instanceof Mek) {
            if (en.infernos.isStillBurning()) { // hit with inferno ammo
                currentHeatBuildup += en.infernos.getHeat();
            }

            // extreme temperatures.
            if ((game != null) && (game.getPlanetaryConditions().getTemperature() > 0)) {
                int buildup = game.getPlanetaryConditions().getTemperatureDifference(50, -30);
                if (((Mek) en).hasIntactHeatDissipatingArmor()) {
                    buildup /= 2;
                }
                currentHeatBuildup += buildup;
            } else if (game != null) {
                currentHeatBuildup -= game.getPlanetaryConditions().getTemperatureDifference(50, -30);
            }
        }
        Coords position = entity.getPosition();
        if ((game != null) && !en.isOffBoard() && game.hasBoardLocation(position, entity.getBoardId())) {
            Hex hex = game.getBoard(entity.getBoardId()).getHex(position);
            if (hex != null) {
                if (hex.containsTerrain(Terrains.FIRE) && (hex.getFireTurn() > 0)) {
                    // standing in fire
                    if ((en instanceof Mek) && ((Mek) en).hasIntactHeatDissipatingArmor()) {
                        currentHeatBuildup += 2;
                    } else {
                        currentHeatBuildup += 5;
                    }
                }

                if (hex.terrainLevel(Terrains.MAGMA) == 1) {
                    if ((en instanceof Mek) && ((Mek) en).hasIntactHeatDissipatingArmor()) {
                        currentHeatBuildup += 2;
                    } else {
                        currentHeatBuildup += 5;
                    }
                } else if (hex.terrainLevel(Terrains.MAGMA) == 2) {
                    if ((en instanceof Mek) && ((Mek) en).hasIntactHeatDissipatingArmor()) {
                        currentHeatBuildup += 5;
                    } else {
                        currentHeatBuildup += 10;
                    }
                }
            } else {
                logger.warn("An entity is not offboard but has a position not on board.");
            }
        }

        if ((((en instanceof Mek) || (en instanceof Aero)) && en.isStealthActive())
              || en.isNullSigActive() || en.isVoidSigActive()) {
            currentHeatBuildup += 10; // active stealth/null sig/void sig heat
        }

        if ((en instanceof Mek) && en.isChameleonShieldOn()) {
            currentHeatBuildup += 6;
        }

        if (((en instanceof Mek) || (en instanceof Aero)) && en.hasActiveNovaCEWS()) {
            currentHeatBuildup += 2;
        }

        // on large craft we may need to take account of firing arcs
        boolean[] usedFrontArc = new boolean[entity.locations()];
        boolean[] usedRearArc = new boolean[entity.locations()];
        for (int i = 0; i < entity.locations(); i++) {
            usedFrontArc[i] = false;
            usedRearArc[i] = false;
        }

        boolean hasFiredWeapons = false;
        for (int i = 0; i < entity.getWeaponListWithHHW().size(); i++) {
            WeaponMounted mounted = entity.getWeaponListWithHHW().get(i);
            if (!isListed(entity, mounted)) {
                continue;
            }

            if (mounted.isUsedThisRound() && game != null
                  && (game.getPhase() == mounted.usedInPhase())
                  && game.getPhase().isFiring()) {
                hasFiredWeapons = true;
                // add heat from weapons fire to heat tracker
                if (entity.isLargeCraft()) {
                    // if using bay heat option then don't add total arc
                    if (game.getOptions().booleanOption(OptionsConstants.ADVANCED_AERO_RULES_HEAT_BY_BAY)) {
                        currentHeatBuildup += mounted.getHeatByBay();
                    } else {
                        // check whether arc has fired
                        int loc = mounted.getLocation();
                        boolean rearMount = mounted.isRearMounted();
                        if (!rearMount) {
                            if (!usedFrontArc[loc]) {
                                currentHeatBuildup += entity.getHeatInArc(loc, rearMount);
                                usedFrontArc[loc] = true;
                            }
                        } else {
                            if (!usedRearArc[loc]) {
                                currentHeatBuildup += entity.getHeatInArc(loc, rearMount);
                                usedRearArc[loc] = true;
                            }
                        }
                    }
                } else {
                    if (!mounted.isBombMounted() && entity.equals(mounted.getEntity())) {
                        currentHeatBuildup += mounted.getHeatByBay();
                    }
                }
            }
        }
        if (en.hasDamagedRHS() && hasFiredWeapons) {
            currentHeatBuildup++;
        }

        String combatComputerIndicator = "";
        if (en.hasQuirk(OptionsConstants.QUIRK_POS_COMBAT_COMPUTER)) {
            currentHeatBuildup -= 4;
            combatComputerIndicator = " \uD83D\uDCBB";
        }

        // check for negative values due to extreme temp
        if (currentHeatBuildup < 0) {
            currentHeatBuildup = 0;
        }

        String heatText = Integer.toString(currentHeatBuildup);
        UnitToolTip.HeatDisplayHelper hdh = UnitToolTip.getHeatCapacityForDisplay(en);
        String heatCapacityStr = hdh.heatCapacityStr;
        int heatOverCapacity = currentHeatBuildup - hdh.heatCapWater;

        String sheatOverCapacity = "";
        if (heatOverCapacity > 0) {
            heatText += "*"; // overheat indication
            String msg_over = Messages.getString("MekDisplay.over");
            sheatOverCapacity = " " + heatOverCapacity + " " + msg_over;
        }

        String heatMessage = heatText + " (" + heatCapacityStr + ')' + sheatOverCapacity;
        String tempIndicator = "";

        if ((game != null) && (game.getPlanetaryConditions().isExtremeTemperature())) {
            tempIndicator = " " + game.getPlanetaryConditions().getTemperatureIndicator();
        }

        heatMessage += combatComputerIndicator + tempIndicator;

        return new HeatBuildup(currentHeatBuildup, heatOverCapacity, heatMessage);
    }

    /**
     * The damage the weapon display shows for a weapon of the unit: its damage per hit, "Missile" or "Variable" for
     * cluster weapons, the artillery damage from the impact hex outwards, a TacOps energy weapon's dialed damage, or,
     * for an aerospace attack, whose attack values the range display shows, "Standard" or "Capital". The game is the
     * client's game, null without a client. The Unit Display and the GPU record sheet both use it.
     */
    public static String damageText(@Nullable Game game, Entity entity, WeaponMounted mounted) {
        WeaponType weaponType = mounted.getType();
        if (isAerospaceAttack(entity)) {
            // a statement of standard or capital damage
            return Messages.getString(weaponType.isCapital() ? "MekDisplay.CapitalD" : "MekDisplay.StandardD");
        }
        String text;
        if (weaponType.getDamage() == WeaponType.DAMAGE_BY_CLUSTER_TABLE) {
            if (weaponType instanceof HAGWeapon) {
                text = Messages.getString("MekDisplay.Variable");
            } else {
                text = Messages.getString("MekDisplay.Missile");
            }
        } else if (weaponType.getDamage() == WeaponType.DAMAGE_VARIABLE) {
            text = Messages.getString("MekDisplay.Variable");
        } else if (weaponType.getDamage() == WeaponType.DAMAGE_SPECIAL) {
            text = Messages.getString("MekDisplay.Special");
        } else if (weaponType.getDamage() == WeaponType.DAMAGE_ARTILLERY) {
            StringBuilder damage = new StringBuilder();
            int artyDamage = weaponType.getRackSize();
            int falloff = 10;
            boolean fuelAirExplosive = false;
            boolean specialArrowIV = false;
            if ((mounted.getLinked() != null) && (mounted.getLinked().getType() instanceof AmmoType ammoType)) {
                fuelAirExplosive = ammoType.getMunitionType().contains(Munitions.M_FAE);
                specialArrowIV = (ammoType.is(AmmoTypeEnum.ARROW_IV)
                      && (ammoType.getMunitionType().contains(AmmoType.Munitions.M_ADA)
                      || ammoType.getMunitionType().contains(AmmoType.Munitions.M_HOMING)));
                int attackingBA = (entity instanceof BattleArmor) ? ((BattleArmor) entity).getShootingStrength() : -1;
                DamageFalloff damageFalloff = AreaEffectHelper.calculateDamageFallOff(ammoType, attackingBA, false);
                artyDamage = damageFalloff.damage;
                falloff = damageFalloff.falloff;
            }
            damage.append(artyDamage);
            if (!specialArrowIV) {
                artyDamage -= falloff;
                while ((artyDamage > 0) && (falloff > 0)) {
                    damage.append('/').append(artyDamage);
                    artyDamage -= falloff;
                }
                if (fuelAirExplosive) {
                    damage.append("/5");
                }
            }
            text = damage.toString();
        } else if (weaponType.hasFlag(WeaponType.F_ENERGY)
              && mounted.hasModes()
              && (game != null)
              && game.getOptions().booleanOption(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_ENERGY_WEAPONS)) {
            if (mounted.hasChargedCapacitor() == 1) {
                text = Integer.toString(Compute.dialDownDamage(mounted, weaponType) + 5);
            } else if (mounted.hasChargedCapacitor() == 2) {
                text = Integer.toString(Compute.dialDownDamage(mounted, weaponType) + 10);
            } else {
                text = Integer.toString(Compute.dialDownDamage(mounted, weaponType));
            }
        } else if (mounted.curMode().getName().contains("Dazzle")) {
            // Gothic Dazzle Mode: half damage (rounded down, min 1)
            int baseDamage = weaponType.getDamage();
            int dazzleDamage = Math.max(1, baseDamage / 2);
            text = Integer.toString(dazzleDamage);
        } else {
            text = Integer.toString(weaponType.getDamage());
        }

        if (mounted.getType().hasFlag(WeaponType.F_BOMBAST_LASER)) {
            int damage = (mounted.curMode().equals("Damage 16")) ? 16 : (mounted.curMode().equals("Damage 12")) ? 12 :
                                                                         8;
            text = Integer.toString(damage);
        }
        return text;
    }

    /**
     * Whether the unit attacks as an aerospace unit, with attack values by range bracket: airborne, or a unit with
     * weapon bays. The rules are a bit sparse on airborne (dropping) ground units, but it seems they should still
     * attack like ground units.
     */
    public static boolean isAerospaceAttack(Entity entity) {
        return entity.isAero() && (entity.isAirborne() || entity.usesWeaponBays());
    }

    /**
     * An ammunition list entry of the unit's weapon: the bin's location (or its place in a train), its description
     * without the shots, and hot-loading. The Unit Display and the GPU unit record both use it.
     */
    public static String formatAmmo(Entity entity, Mounted<?> m) {
        StringBuilder sb = new StringBuilder(64);
        int ammoIndex = m.getDesc().indexOf(Messages.getString("MekDisplay.0"));
        int loc = m.getLocation();
        if (!m.getEntity().equals(entity) && !(m.getEntity() instanceof HandheldWeapon)) {
            // Name the unit's place in the train rather than just saying the ammo is elsewhere on it. A convoy can
            // carry several identical carriages, so "TL2" is the only thing that says which one this is.
            String trainPosition = UIUtil.trainPositionLabel(m.getEntity());
            sb.append('[').append(trainPosition.isEmpty() ? "TR" : trainPosition).append("] ");
        } else if (loc != Entity.LOC_NONE) {
            sb.append('[').append(entity.getLocationAbbr(loc)).append("] ");
        }
        if (ammoIndex == -1) {
            sb.append(m.getDesc());
        } else {
            sb.append(m.getDesc(), 0, ammoIndex);
            sb.append(m.getDesc().substring(ammoIndex + 4));
        }
        if (m.isHotLoaded()) {
            sb.append(Messages.getString("MekDisplay.isHotLoaded"));
        }
        return sb.toString();
    }

    /**
     * Formats an artillery weapon's range as extended by the Oblique Artilleryman ability - ten percent more range
     * (CamOps p.78, 5th printing) - marked with an asterisk so the reader can tell it apart from the weapon's rated
     * range. Artillery ranges are given in map sheets, and ten percent of one is a fraction, so the extended figure
     * keeps its decimal.
     *
     * @param ratedRangeInMapSheets the weapon's rated range, in map sheets
     *
     * @return the extended range, marked with an asterisk
     */
    private static String extendedArtilleryRange(int ratedRangeInMapSheets) {
        return Messages.getString("MekDisplay.ObliqueArtillerymanRange",
              ArtilleryRange.extendedRangeInMapSheets(ratedRangeInMapSheets));
    }

    /**
     * The weapon display's statistics of one weapon, as its labels show them. The Unit Display and the GPU unit record
     * both use it.
     *
     * @param heat           the heat (a weapon bay: the heat of its usable weapons)
     * @param arcHeat        the heat of the weapon's firing arc
     * @param damage         {@link #damageText}
     * @param byTrooper      an infantry weapon's damage per trooper, "" for any other weapon
     * @param infantryRanges an infantry weapon's range brackets with their to-hit modifiers, empty for other weapons
     * @param min            the minimum range, "---" for none
     * @param shortRange     the short range bracket
     * @param mediumRange    the medium range bracket
     * @param longRange      the long range bracket
     * @param extremeRange   the extreme range bracket
     * @param extendedByCrew whether the long and extreme ranges include the Oblique Artilleryman's extension
     * @param extremeShown   whether the display shows the extreme range ({@link #showsExtremeRange})
     * @param attackValues   an aerospace attack's short, medium, long and extreme attack values, empty otherwise
     */
    public record WeaponStats(String heat, String arcHeat, String damage, String byTrooper,
          List<InfantryRange> infantryRanges, String min, String shortRange, String mediumRange, String longRange,
          String extremeRange, boolean extendedByCrew, boolean extremeShown, List<String> attackValues) {
        public WeaponStats {
            infantryRanges = List.copyOf(infantryRanges);
            attackValues = List.copyOf(attackValues);
        }
    }

    /** One of an infantry weapon's range brackets ("1-2") and its to-hit modifier ("+0"). */
    public record InfantryRange(String range, String modifier) { }

    /** An aerospace attack's heat (null: the weapon's own), attack values and range brackets from short to extreme. */
    private record AerospaceValues(@Nullable String heat, List<String> attackValues, List<String> ranges) { }

    /**
     * The statistics the weapon display shows for a weapon of the unit with the given ammunition (null: none); the
     * game is the client's, null without a client. A CWS weapon has its long ranges only against a target susceptible
     * to it.
     */
    public static WeaponStats stats(@Nullable Game game, Entity entity, WeaponMounted mounted,
          @Nullable AmmoMounted ammo, boolean cwsSusceptibleTarget) {
        WeaponType weaponType = mounted.getType();
        // The rules are a bit sparse on airborne (dropping) ground units, but it seems they should still attack like
        // ground units.
        boolean aerospaceAttack = isAerospaceAttack(entity);
        String byTrooper = "";
        List<InfantryRange> infantryRanges = List.of();
        if ((weaponType instanceof InfantryWeapon infantryType) && !weaponType.hasFlag(WeaponType.F_TAG)) {
            byTrooper = (entity instanceof ConvInfantry infantry)
                  ? Double.toString((double) Math.round(infantry.getDamagePerTrooper() * 1000) / 1000)
                  : Double.toString(infantryType.getInfantryDamage());
            infantryRanges = infantryRanges(entity, mounted, infantryType);
        }
        // The Oblique Artilleryman ability extends an artillery weapon's range by ten percent (CamOps p.78, 5th
        // printing). An aerospace unit's display shows attack values instead of ranges, so it is left alone.
        boolean extendedByCrew = ArtilleryRange.isExtendedByObliqueArtilleryman(entity, weaponType)
              && !aerospaceAttack;
        String[] ranges = ranges(entity, mounted, cwsSusceptibleTarget, extendedByCrew);
        if (ammo != null) {
            applyAmmoRanges(ranges, ammo);
        }
        String heat = Integer.toString(mounted.getCurrentHeat());
        List<String> attackValues = List.of();
        if (aerospaceAttack) {
            // A weapons bay is compiled from its weapons; any other weapon shows standard ranges and attack values
            AerospaceValues values = (weaponType instanceof BayWeapon)
                  ? bayAttackValues(entity, mounted, ammo, weaponType.isCapital())
                  : attackValues(entity, mounted, ammo);
            if (values.heat() != null) {
                heat = values.heat();
            }
            attackValues = values.attackValues();
            for (int bracket = 0; bracket < 4; bracket++) {
                ranges[bracket + 1] = values.ranges().get(bracket);
            }
        }
        return new WeaponStats(heat,
              Integer.toString(entity.getHeatInArc(mounted.getLocation(), mounted.isRearMounted())),
              damageText(game, entity, mounted), byTrooper, infantryRanges, ranges[0], ranges[1], ranges[2],
              ranges[3], ranges[4], extendedByCrew, showsExtremeRange(entity.getGame(), entity), attackValues);
    }

    /** Whether the weapon display shows the extreme range: under the TacOps range rules, and for aerospace attacks. */
    public static boolean showsExtremeRange(@Nullable Game game, Entity entity) {
        return ((game != null) && game.getOptions().booleanOption(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_RANGE))
              || isAerospaceAttack(entity);
    }

    /** An infantry weapon's range brackets from point blank outwards, each with its to-hit modifier. */
    private static List<InfantryRange> infantryRanges(Entity entity, WeaponMounted mounted,
          InfantryWeapon infantryType) {
        int zeroMods = 0;
        if (infantryType.hasFlag(WeaponType.F_INF_POINT_BLANK)) {
            zeroMods++;
        }
        if (infantryType.hasFlag(WeaponType.F_INF_ENCUMBER) || (infantryType.getCrew() > 1)) {
            zeroMods++;
        }
        if (infantryType.hasFlag(WeaponType.F_INF_BURST)) {
            zeroMods--;
        }
        int range = infantryType.getInfantryRange();
        if (entity.getLocationStatus(mounted.getLocation()) == ILocationExposureStatus.WET) {
            range /= 2;
        }
        String closeTwo = Integer.toString(zeroMods - 2);
        String closeOne = Integer.toString(zeroMods - 1);
        return switch (range) {
            case 0 -> List.of(new InfantryRange("0", "+" + zeroMods));
            case 1 -> brackets("0", closeTwo, "1", "+0", "2", "+2", "3", "+4");
            case 2 -> brackets("0", closeTwo, "1-2", "+0", "3-4", "+2", "5-6", "+4");
            case 3 -> brackets("0", closeTwo, "1-3", "+0", "4-6", "+2", "7-9", "+4");
            case 4 -> brackets("0", closeTwo, "1-4", "+0", "5-6", "+1", "7-8", "+2", "9-10", "+3", "11-12", "+4");
            case 5 -> brackets("0", closeOne, "1-5", "+0", "6-7", "+1", "8-10", "+2", "11-12", "+3", "13-15", "+4");
            case 6 -> brackets("0", closeOne, "1-6", "+0", "7-9", "+1", "10-12", "+2", "13-15", "+4", "16-18", "+5");
            case 7 -> brackets("0", closeOne, "1-7", "+0", "8-10", "+1", "11-14", "+2", "15-17", "+4", "18-21", "+6");
            default -> List.of();
        };
    }

    /** Range brackets from alternating range and modifier texts. */
    private static List<InfantryRange> brackets(String... texts) {
        List<InfantryRange> brackets = new ArrayList<>();
        for (int index = 0; index < texts.length; index += 2) {
            brackets.add(new InfantryRange(texts[index], texts[index + 1]));
        }
        return brackets;
    }

    /** A weapon's minimum, short, medium, long and extreme range texts, before its ammunition changes them. */
    private static String[] ranges(Entity entity, WeaponMounted mounted, boolean cwsSusceptibleTarget,
          boolean extendedByCrew) {
        WeaponType weaponType = mounted.getType();
        int shortR = weaponType.getShortRange();
        int mediumR = weaponType.getMediumRange();
        int longR = weaponType.getLongRange();
        int extremeR = weaponType.getExtremeRange();
        if (mounted.isInBearingsOnlyMode()) {
            extremeR = RangeType.RANGE_BEARINGS_ONLY_OUT;
        }
        // Show water ranges for submerged weapons and those that have only water ranges (torpedoes)
        if ((entity.getLocationStatus(mounted.getLocation()) == ILocationExposureStatus.WET)
              || ((longR == 0) && weaponType.getWLongRange() > 0)) {
            shortR = Game.rulesManager.getRulesUnderwater().getShortRange(weaponType);
            mediumR = Game.rulesManager.getRulesUnderwater().getMediumRange(weaponType);
            longR = Game.rulesManager.getRulesUnderwater().getLongRange(weaponType);
            extremeR = Game.rulesManager.getRulesUnderwater().getExtremeRange(weaponType);
        } else if (weaponType.hasFlag(WeaponType.F_PD_BAY)) {
            // Point Defense bays have a variable range, depending on the mode they're in
            shortR = (mounted.hasModes() && mounted.curMode().equals("Point Defense")) ? 1 : 6;
        }
        // Centurion Weapon Systems have their 6/12/18 ranges only against units susceptible to them, else 1/2/3
        if (weaponType.hasFlag(WeaponType.F_CWS) && !cwsSusceptibleTarget) {
            shortR = 1;
            mediumR = 2;
            longR = 3;
            extremeR = 4;
        }
        // Artillery ranges are rated in map sheets and the extension is a fraction of one, so the extended figure is
        // shown with a decimal and marked with an asterisk.
        String longRangeText = extendedByCrew ? extendedArtilleryRange(longR) : Integer.toString(longR);
        String extremeRangeText = extendedByCrew ? extendedArtilleryRange(extremeR) : Integer.toString(extremeR);
        return new String[] {
              (weaponType.getMinimumRange() > 0) ? Integer.toString(weaponType.getMinimumRange()) : "---",
              (shortR > 1) ? "1 - " + shortR : Integer.toString(shortR),
              ((mediumR - shortR) > 1) ? (shortR + 1) + " - " + mediumR : Integer.toString(mediumR),
              ((longR - mediumR) > 1) ? (mediumR + 1) + " - " + longRangeText : longRangeText,
              ((extremeR - longR) > 1) ? (longR + 1) + " - " + extremeRangeText : extremeRangeText };
    }

    /**
     * Replaces the range texts (minimum, short, medium, long, extreme) for the ATM, MML, iATM, Dead-Fire and Arrow IV
     * ADA ammunition that has ranges of its own, and the minimum range of hot-loaded ammunition; the others stay.
     */
    private static void applyAmmoRanges(String[] ranges, AmmoMounted mAmmo) {
        AmmoType ammoType = mAmmo.getType();
        EnumSet<Munitions> munitions = ammoType.getMunitionType();
        // Only override the display for the various ATM and MML ammunition
        if ((ammoType.getAmmoType() == AmmoTypeEnum.ATM) || (ammoType.getAmmoType() == AmmoTypeEnum.IATM)) {
            if (munitions.contains(Munitions.M_EXTENDED_RANGE)) {
                setRanges(ranges, "4", "1 - 9", "10 - 18", "19 - 27", "28 - 36");
            } else if (munitions.contains(Munitions.M_HIGH_EXPLOSIVE)
                  || ((ammoType.getAmmoType() == AmmoTypeEnum.IATM) && munitions.contains(Munitions.M_IATM_IMP))) {
                setRanges(ranges, "---", "1 - 3", "4 - 6", "7 - 9", "10 - 12");
            } else {
                // standard, and the iATM's IIW
                setRanges(ranges, "4", "1 - 5", "6 - 10", "11 - 15", "16 - 20");
            }
        } else if (ammoType.getAmmoType() == AmmoTypeEnum.MML) {
            if (ammoType.hasFlag(AmmoType.F_MML_LRM)) {
                if (munitions.contains(Munitions.M_DEAD_FIRE)) {
                    setRanges(ranges, "4", "1 - 5", "6 - 10", "11 - 15", "16 - 20");
                } else {
                    setRanges(ranges, "6", "1 - 7", "8 - 14", "15 - 21", "21 - 28");
                }
            } else if (munitions.contains(Munitions.M_DEAD_FIRE)) {
                setRanges(ranges, "---", "1 - 2", "3 - 4", "5 - 6", "7 - 8");
            } else {
                setRanges(ranges, "---", "1 - 3", "4 - 6", "7 - 9", "10 - 12");
            }
        } else if ((ammoType.getAmmoType() == AmmoTypeEnum.LRM) && munitions.contains(Munitions.M_DEAD_FIRE)) {
            setRanges(ranges, "4", "1 - 5", "6 - 10", "11 - 15", "16 - 20");
        } else if ((ammoType.getAmmoType() == AmmoTypeEnum.SRM) && munitions.contains(Munitions.M_DEAD_FIRE)) {
            setRanges(ranges, "---", "1 - 2", "3 - 4", "5 - 6", "7 - 8");
        } else if ((ammoType.getAmmoType() == AmmoTypeEnum.ARROW_IV) && munitions.contains(Munitions.M_ADA)) {
            // Special casing for ADA ranges
            setRanges(ranges, "---", "1 - 17 [0]", "18 - 34 [1]", "35 - 51 [2]", "---");
        }
        // Min range 0 for hot load
        if (mAmmo.isHotLoaded()) {
            ranges[0] = "---";
        }
    }

    private static void setRanges(String[] ranges, String... texts) {
        for (int index = 0; index < ranges.length; index++) {
            ranges[index] = texts[index];
        }
    }

    /** An airborne weapon's attack values and standard ranges, with its ammunition's changes (null: none). */
    private static AerospaceValues attackValues(Entity entity, WeaponMounted weapon, @Nullable AmmoMounted ammo) {
        WeaponType weaponType = weapon.getType();
        // update Attack Values and change range
        int avShort = weaponType.getRoundShortAV();
        int avMed = weaponType.getRoundMedAV();
        int avLong = weaponType.getRoundLongAV();
        int avExt = weaponType.getRoundExtAV();
        int maxRange = weaponType.getMaxRange(weapon);

        // change range and attack values based upon ammo
        if (ammo != null) {
            double[] changes = changeAttackValues(ammo.getType(), avShort, avMed, avLong, avExt, maxRange);
            avShort = (int) changes[0];
            avMed = (int) changes[1];
            avLong = (int) changes[2];
            avExt = (int) changes[3];
            maxRange = (int) changes[4];
        }

        if (entity.getGame().getOptions().booleanOption(OptionsConstants.ADVANCED_AERO_RULES_AERO_SANITY)
              && weaponType.isCapital()) {
            avShort *= 10;
            avMed *= 10;
            avLong *= 10;
            avExt *= 10;
        }

        // range labels follow the range scale, which can differ from the damage scale
        boolean usesCapitalRangeBrackets = weaponType.usesCapitalRangeBrackets();
        String shortRange;
        if (usesCapitalRangeBrackets) {
            shortRange = "1-12";
        } else if (weaponType.hasFlag(WeaponType.F_PD_BAY)) {
            // Point Defense bays have a variable range too, depending on the mode they're in
            shortRange = (weapon.hasModes() && weapon.curMode().equals("Point Defense")) ? "1" : "1-6";
        } else {
            shortRange = "1-6";
        }
        return aerospaceValues(null, usesCapitalRangeBrackets, maxRange, shortRange,
              List.of(Integer.toString(avShort), Integer.toString(avMed), Integer.toString(avLong),
                    Integer.toString(avExt)));
    }

    /**
     * A weapon bay's heat, attack values and ranges, compiled from its usable weapons with the bay's ammunition's
     * changes (null: none) and a capital bay's bracketing mode.
     */
    private static AerospaceValues bayAttackValues(Entity entity, WeaponMounted weapon, @Nullable AmmoMounted mAmmo,
          boolean isCapital) {
        WeaponType weaponType = weapon.getType();
        int heat = 0;
        double avShort = 0;
        double avMed = 0;
        double avLong = 0;
        double avExt = 0;
        int maxRange = WeaponType.RANGE_SHORT;

        for (WeaponMounted m : weapon.getBayWeapons()) {
            if (!m.isBreached()
                  && !m.isMissing()
                  && !m.isDestroyed()
                  && !m.isJammed()
                  && ((mAmmo == null) || (mAmmo.getUsableShotsLeft() > 0))) {
                WeaponType bayWType = m.getType();
                heat = heat + m.getCurrentHeat();
                double mAVShort = bayWType.getShortAV();
                double mAVMed = bayWType.getMedAV();
                double mAVLong = bayWType.getLongAV();
                double mAVExt = bayWType.getExtAV();
                int mMaxR = bayWType.getMaxRange(m);

                // deal with any ammo adjustments
                if (mAmmo != null) {
                    double[] changes = changeAttackValues(mAmmo.getType(), mAVShort, mAVMed,
                          mAVLong, mAVExt, mMaxR);
                    mAVShort = changes[0];
                    mAVMed = changes[1];
                    mAVLong = changes[2];
                    mAVExt = changes[3];
                    mMaxR = (int) changes[4];
                }

                avShort = avShort + mAVShort;
                avMed = avMed + mAVMed;
                avLong = avLong + mAVLong;
                avExt = avExt + mAVExt;
                if (mMaxR > maxRange) {
                    maxRange = mMaxR;
                }
            }
        }
        // check for bracketing
        double multiplier = 1.0;
        if (weapon.hasModes() && weapon.curMode().equals("Bracket 80%")) {
            multiplier = 0.8;
        }
        if (weapon.hasModes() && weapon.curMode().equals("Bracket 60%")) {
            multiplier = 0.6;
        }
        if (weapon.hasModes() && weapon.curMode().equals("Bracket 40%")) {
            multiplier = 0.4;
        }
        avShort = multiplier * avShort;
        avMed = multiplier * avMed;
        avLong = multiplier * avLong;
        avExt = multiplier * avExt;

        if (entity.getGame().getOptions().booleanOption(OptionsConstants.ADVANCED_AERO_RULES_AERO_SANITY)
              && weaponType.isCapital()) {
            avShort *= 10;
            avMed *= 10;
            avLong *= 10;
            avExt *= 10;
        }
        return aerospaceValues(Integer.toString(heat), isCapital, maxRange, isCapital ? "1-12" : "1-6",
              List.of(Integer.toString((int) Math.ceil(avShort)), Integer.toString((int) Math.ceil(avMed)),
                    Integer.toString((int) Math.ceil(avLong)), Integer.toString((int) Math.ceil(avExt))));
    }

    /** The attack values and range brackets up to the maximum range; the brackets beyond it show "---". */
    private static AerospaceValues aerospaceValues(@Nullable String heat, boolean capital, int maxRange,
          String shortRange, List<String> attackValues) {
        String none = "---";
        List<String> values = new ArrayList<>(List.of(attackValues.get(0), none, none, none));
        List<String> ranges = new ArrayList<>(List.of(shortRange, none, none, none));
        if (maxRange > WeaponType.RANGE_SHORT) {
            values.set(1, attackValues.get(1));
            ranges.set(1, capital ? "13-24" : "7-12");
        }
        if (maxRange > WeaponType.RANGE_MED) {
            values.set(2, attackValues.get(2));
            ranges.set(2, capital ? "25-40" : "13-20");
        }
        if (maxRange > WeaponType.RANGE_LONG) {
            values.set(3, attackValues.get(3));
            ranges.set(3, capital ? "41-50" : "21-25");
        }
        return new AerospaceValues(heat, List.copyOf(values), List.copyOf(ranges));
    }

    /**
     * The ammunition list of a weapon: how it offers the weapon's ammunition ({@code feed}) and the bins the player may
     * pick ({@code ammo}). The Unit Display and the GPU unit record both use it.
     *
     * @param feed {@code NONE} a weapon without ammunition; {@code CHAIN} a double one-shot launcher (or a support
     *             vehicle's infantry weapon), which offers its linked bins with shots left; {@code FIXED} a one-shot
     *             or static feed weapon, which shows its loaded bin and cannot switch; {@code BINS} every other weapon,
     *             which offers every usable bin it may switch to
     * @param ammo the bins the player may pick, empty for {@code NONE} and {@code FIXED}
     */
    public record AmmoChoices(Feed feed, List<AmmoMounted> ammo) {
        public AmmoChoices {
            ammo = List.copyOf(ammo);
        }

        /** How the ammunition list offers a weapon's ammunition. */
        public enum Feed { NONE, CHAIN, FIXED, BINS }
    }

    /**
     * The ammunition list of a weapon of the unit; {@code listed} is the weapon list's entry, a weapon bay or the
     * weapon itself, and {@code weapon} the weapon whose ammunition is listed (the bay's selected weapon).
     */
    public static AmmoChoices ammoChoices(Entity entity, WeaponMounted listed, WeaponMounted weapon) {
        WeaponType weaponType = weapon.getType();
        if (weaponType.getAmmoType() == AmmoTypeEnum.NA) {
            return new AmmoChoices(AmmoChoices.Feed.NONE, List.of());
        } else if (weaponType.hasFlag(WeaponType.F_DOUBLE_ONE_SHOT)
              || (entity.isSupportVehicle() && (weaponType.getAmmoType() == AmmoTypeEnum.INFANTRY))) {
            List<AmmoMounted> chain = new ArrayList<>();
            for (AmmoMounted current = weapon.getLinkedAmmo(); current != null;
                  current = (AmmoMounted) current.getLinked()) {
                if (current.getUsableShotsLeft() > 0) {
                    chain.add(current);
                }
            }
            return new AmmoChoices(AmmoChoices.Feed.CHAIN, chain);
        } else if (weaponType.hasFlag(WeaponType.F_ONE_SHOT)
              || weapon.hasQuirk(OptionsConstants.QUIRK_WEAPON_NEG_STATIC_FEED)) {
            // Static Ammo Feed weapons are locked to their specific ammo bin (CamOps p.235/BMM p.89)
            return new AmmoChoices(AmmoChoices.Feed.FIXED, List.of());
        }
        List<AmmoMounted> bins = new ArrayList<>();
        // Ammo sharing between adjacent trailers. The server validates against this same rule.
        for (AmmoMounted ammo : TrainAmmoSharing.getSharedAmmo(entity)) {
            AmmoType ammoType = ammo.getType();
            // for all aero units other than fighters, ammo must be located in the same place to be usable
            boolean sameLocationIfLargeAero = true;
            if ((entity instanceof SmallCraft) || (entity instanceof Jumpship)) {
                sameLocationIfLargeAero = (weapon.getLocation() == ammo.getLocation());
            }
            boolean rightBay = true;
            if (entity.usesWeaponBays() && !(entity instanceof FighterSquadron)) {
                rightBay = listed.ammoInBay(entity.getEquipmentNum(ammo));
            }
            // covers the situation where a weapon using non-caseless ammo should not be able to switch to caseless
            // on the fly and vice versa
            boolean canSwitchToAmmo = AmmoType.canSwitchToAmmo(weapon, ammoType);
            if (ammo.isAmmoUsable() && sameLocationIfLargeAero && rightBay && canSwitchToAmmo
                  && (ammoType.getAmmoType() == weaponType.getAmmoType())
                  && (ammoType.getRackSize() == weaponType.getRackSize())) {
                bins.add(ammo);
            }
        }
        return new AmmoChoices(AmmoChoices.Feed.BINS, bins);
    }

    /**
     * Loads a bin into the owner's weapon and tells the server, as picking the bin in the ammunition list does: for a
     * weapon bay, into every weapon of the bay with the type of the bay's weapon {@code bayWeapon} (null: none). The
     * bin may belong to a connected trailer, so its number is resolved against its own carrier. The Unit Display and
     * the GPU unit record both use it.
     *
     * @param weapon    the weapon list's entry: a weapon bay or the weapon itself
     * @param bayWeapon the bay's weapon whose type loads the bin; ignored for a weapon that is no bay
     */
    public static void loadAmmo(Client client, Entity entity, WeaponMounted weapon,
          @Nullable WeaponMounted bayWeapon, AmmoMounted ammo) {
        List<WeaponMounted> loaded = List.of(weapon);
        if (weapon.getType() instanceof BayWeapon) {
            // FIXME: Consider new AmmoType::equals / BombType::equals
            loaded = (bayWeapon == null) ? List.of() : weapon.getBayWeapons().stream()
                  .filter(member -> member.getType().equals(bayWeapon.getType())).toList();
        }
        Entity carrier = ammo.getEntity();
        for (WeaponMounted member : loaded) {
            entity.loadWeapon(member, ammo);
            client.sendAmmoChange(entity.getId(), entity.getEquipmentNum(member), carrier.getEquipmentNum(ammo),
                  carrier.getId(), 0);
        }
    }

    private static double[] changeAttackValues(AmmoType ammoType, double avShort,
          double avMed, double avLong, double avExt, int maxRange) {

        if (AmmoType.AmmoTypeEnum.ATM == ammoType.getAmmoType()) {
            if (ammoType.getMunitionType().contains(AmmoType.Munitions.M_EXTENDED_RANGE)) {
                maxRange = WeaponType.RANGE_EXT;
                avShort = avShort / 2;
                avMed = avMed / 2;
                avLong = avMed;
                avExt = avMed;
            } else if (ammoType.getMunitionType().contains(AmmoType.Munitions.M_HIGH_EXPLOSIVE)) {
                maxRange = WeaponType.RANGE_SHORT;
                avShort = avShort + (avShort / 2);
                avMed = 0;
                avLong = 0;
                avExt = 0;
            }
        } // End weapon-is-ATM
        else if (ammoType.getAmmoType() == AmmoType.AmmoTypeEnum.MML) {
            // first check for artemis
            int bonus = 0;
            if (ammoType.getMunitionType().contains(AmmoType.Munitions.M_ARTEMIS_CAPABLE)) {
                int rack = ammoType.getRackSize();
                if (rack == 5) {
                    bonus += 1;
                } else if (rack >= 7) {
                    bonus += 2;
                }
                avShort = avShort + bonus;
                avMed = avMed + bonus;
                avLong = avLong + bonus;
            }
            if (!ammoType.hasFlag(AmmoType.F_MML_LRM)) {
                maxRange = WeaponType.RANGE_SHORT;
                avShort = avShort * 2;
                avMed = 0;
                avLong = 0;
                avExt = 0;
            }
        } // end weapon is MML
        else if ((ammoType.getAmmoType() == AmmoType.AmmoTypeEnum.LRM)
              || (ammoType.getAmmoType() == AmmoType.AmmoTypeEnum.LRM_IMP)
              || (ammoType.getAmmoType() == AmmoType.AmmoTypeEnum.SRM)
              || (ammoType.getAmmoType() == AmmoType.AmmoTypeEnum.SRM_IMP)) {

            if (ammoType.getMunitionType().contains(AmmoType.Munitions.M_ARTEMIS_CAPABLE)) {
                if ((ammoType.getAmmoType() == AmmoType.AmmoTypeEnum.LRM) || (ammoType.getAmmoType()
                      == AmmoType.AmmoTypeEnum.LRM_IMP)) {
                    int bonus = (int) Math.ceil(ammoType.getRackSize() / 5.0);
                    avShort = avShort + bonus;
                    avMed = avMed + bonus;
                    avLong = avLong + bonus;
                }
                if ((ammoType.getAmmoType() == AmmoType.AmmoTypeEnum.SRM) || (ammoType.getAmmoType()
                      == AmmoType.AmmoTypeEnum.SRM_IMP)) {
                    avShort = avShort + 2;
                }
            }
        } else if (ammoType.getAmmoType() == AmmoType.AmmoTypeEnum.AC_LBX) {
            if (ammoType.getMunitionType().contains(AmmoType.Munitions.M_CLUSTER)) {
                int newAV = (int) Math.floor(0.6 * ammoType.getRackSize());
                avShort = newAV;
                if (avMed > 0) {
                    avMed = newAV;
                }
                if (avLong > 0) {
                    avLong = newAV;
                }
                if (avExt > 0) {
                    avExt = newAV;
                }
            }
        } else if (ammoType.getAmmoType() == AmmoType.AmmoTypeEnum.AR10) {
            if (ammoType.hasFlag(AmmoType.F_AR10_KILLER_WHALE)) {
                avShort = 4;
                avMed = 4;
                avLong = 4;
                avExt = 4;
            } else if (ammoType.hasFlag(AmmoType.F_AR10_WHITE_SHARK)) {
                avShort = 3;
                avMed = 3;
                avLong = 3;
                avExt = 3;
            } else if (ammoType.hasFlag(AmmoType.F_SANTA_ANNA)) {
                avShort = 100;
                avMed = 100;
                avLong = 100;
                avExt = 100;
            } else if (ammoType.hasFlag(AmmoType.F_PEACEMAKER)) {
                avShort = 1000;
                avMed = 1000;
                avLong = 1000;
                avExt = 1000;
            } else {
                avShort = 2;
                avMed = 2;
                avLong = 2;
                avExt = 2;
            }
        } else if (ammoType.getAmmoType() == AmmoType.AmmoTypeEnum.KILLER_WHALE) {
            if (ammoType.hasFlag(AmmoType.F_PEACEMAKER)) {
                avShort = 1000;
                avMed = 1000;
                avLong = 1000;
                avExt = 1000;
            } else {
                avShort = 4;
                avMed = 4;
                avLong = 4;
                avExt = 4;
            }
        } else if (ammoType.getAmmoType() == AmmoType.AmmoTypeEnum.WHITE_SHARK) {
            if (ammoType.hasFlag(AmmoType.F_SANTA_ANNA)) {
                avShort = 100;
                avMed = 100;
                avLong = 100;
                avExt = 100;
            } else {
                avShort = 3;
                avMed = 3;
                avLong = 3;
                avExt = 3;
            }
        }

        return new double[] { avShort, avMed, avLong, avExt, maxRange };

    }

    /**
     * The weapons the weapon list shows for the unit, in the unit's weapon sort order. The Unit Display and the GPU
     * record sheet both use it.
     */
    public static List<WeaponMounted> listedWeapons(Entity entity) {
        List<WeaponMounted> weapons = new ArrayList<>();
        for (WeaponMounted mounted : entity.getWeaponListWithHHW()) {
            if (isListed(entity, mounted)) {
                weapons.add(mounted);
            }
        }
        weapons.sort(entity.getWeaponSortOrder().getWeaponSortComparator(entity));
        return weapons;
    }

    /**
     * Whether the weapon could be declared in the phase at all, as the attack rules allow
     * ({@code ComputeToHitIsImpossible}'s phase reasons): in the Targeting phase only artillery, capital and
     * sub-capital weapons and capital missiles; in the Off-board phase only TAG; in any other phase every weapon. Whether
     * a particular target allows the attack is still that attack's own to-hit check.
     */
    public static boolean isDeclarableIn(GamePhase phase, WeaponMounted weapon) {
        WeaponType type = weapon.getType();
        if (phase.isTargeting()) {
            boolean isArtillery = type.hasFlag(WeaponType.F_ARTILLERY);
            boolean isCapitalMissile = (type instanceof CapitalMissileWeapon) || weapon.isInBearingsOnlyMode();
            boolean isCapitalScale = type.isCapital() || type.isSubCapital();
            return isArtillery || isCapitalMissile || isCapitalScale;
        }
        if (phase.isOffboard()) {
            return type.hasFlag(WeaponType.F_TAG);
        }
        return true;
    }

    /** Whether the list shows the weapon: a LAM in Mek mode lists no bomb weapons but rocket launchers and TAG. */
    public static boolean isListed(Entity entity, WeaponMounted mounted) {
        return !((entity instanceof LandAirMek)
              && (entity.getConversionMode() == LandAirMek.CONV_MODE_MEK)
              && mounted.getType().hasFlag(WeaponType.F_BOMB_WEAPON)
              && mounted.getType().getAmmoType() != AmmoType.AmmoTypeEnum.RL_BOMB
              && !mounted.getType().hasFlag(WeaponType.F_TAG));
    }

    /**
     * Sends the unit's weapon sort order (and custom order) to the server when the local player owns the unit; the sort
     * order list's action. The Unit Display and the GPU record sheet both use it.
     */
    public static void sendWeaponOrder(Client client, Entity entity) {
        if (entity.getOwner().equals(client.getLocalPlayer())) {
            client.sendEntityWeaponOrderUpdate(entity);
        }
    }

    /** Moves a listed weapon to another listed weapon's position, preserving the order of the rest. */
    public static void moveWeapon(Entity entity, WeaponMounted weapon, WeaponMounted destination) {
        List<WeaponMounted> order = new ArrayList<>(listedWeapons(entity));
        int from = order.indexOf(weapon);
        int to = order.indexOf(destination);
        if (from >= 0 && to >= 0 && from != to) {
            order.add(to, order.remove(from));
            setCustomWeaponOrder(entity, order);
        }
    }

    /**
     * Makes the given weapon order the unit's custom order and switches the unit to the custom sort order; the list
     * drag's action. Like the drag, it only marks the order as changed: the phase displays send it when they next
     * switch or finish the unit. The Unit Display and the GPU record sheet both use it.
     */
    public static void setCustomWeaponOrder(Entity entity, List<WeaponMounted> order) {
        if (!entity.getWeaponSortOrder().isCustom()) {
            entity.setWeaponSortOrder(WeaponSortOrder.CUSTOM);
        }
        for (int i = 0; i < order.size(); i++) {
            entity.setCustomWeaponOrder(order.get(i), i);
        }
    }
}
