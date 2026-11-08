/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.annotation.Nullable;
import megamek.client.ratgenerator.FactionRecord;
import megamek.client.ratgenerator.GenerationContext;
import megamek.client.ratgenerator.MissionRole;
import megamek.client.ratgenerator.ModelRecord;
import megamek.client.ratgenerator.UnitTable;
import megamek.common.loaders.MekSummary;
import megamek.common.units.EntityMovementMode;
import megamek.common.units.EntityWeightClass;
import megamek.common.units.UnitType;

/**
 * A random assignment table the RAT Generator builds for a faction, without a toolkit, as the Swing
 * {@code RandomArmyDialog}'s RAT Generator tab asks for it: a unit type, its weight classes, mission roles and how
 * strictly they apply, a C3 network and the motive types. The hand is dealt from the table, and a row of it can be
 * taken as a card of its own.
 * <p>
 * Labels are message keys, for a view to resolve.
 */
public final class RatGeneratorForm implements RandomArmyForm.Dealer {

    /** A choice offered: the message key of its label and its value. */
    public record Choice<T>(String key, T value) { }

    /** Builds a table; {@link UnitTable#findTable} once the RAT Generator has loaded the year. */
    @FunctionalInterface
    public interface TableFinder {
        UnitTable find(FactionRecord faction, int unitType, int year, @Nullable String rating, List<Integer> weights,
              int network, List<EntityMovementMode> motives, List<MissionRole> roles, int strictness);
    }

    /** The unit types offered, in the Swing tab's order. */
    public static final List<Integer> UNIT_TYPES = List.of(UnitType.MEK, UnitType.TANK, UnitType.BATTLE_ARMOR,
          UnitType.INFANTRY, UnitType.PROTOMEK, UnitType.VTOL, UnitType.NAVAL, UnitType.CONV_FIGHTER,
          UnitType.AEROSPACE_FIGHTER, UnitType.SMALL_CRAFT, UnitType.DROPSHIP, UnitType.JUMPSHIP, UnitType.WARSHIP,
          UnitType.SPACE_STATION, UnitType.ADVANCED_BUILDING);

    /** The role strictness levels: Low, Medium and High. */
    public static final List<String> STRICTNESS_KEYS = List.of("RandomArmyDialog.Low", "RandomArmyDialog.Medium",
          "RandomArmyDialog.High");

    private record Request(String faction, int unitType, int year, @Nullable String rating, List<Integer> weights,
          int network, List<EntityMovementMode> motives, List<MissionRole> roles, int strictness) { }

    private final FactionChoice factions;
    private final TableFinder finder;
    private int unitType = UnitType.MEK;
    private List<Integer> weightClasses = List.of();
    private final List<Integer> weights = new ArrayList<>();
    private List<MissionRole> offeredRoles = List.of();
    private final List<MissionRole> roles = new ArrayList<>();
    private int strictness = 2;
    private List<Choice<Integer>> networks = List.of();
    private int network = ModelRecord.NETWORK_NONE;
    private final Map<String, Boolean> motives = new LinkedHashMap<>();
    private final Map<String, String> motiveKeys = new LinkedHashMap<>();
    private @Nullable Request request;
    private @Nullable UnitTable table;

    public RatGeneratorForm(FactionChoice factions, TableFinder finder) {
        this.factions = Objects.requireNonNull(factions);
        this.finder = Objects.requireNonNull(finder);
        refreshUnitType();
    }

    /** The year, faction, command and rating the table is built for. */
    public FactionChoice factions() {
        return factions;
    }

    public int unitType() {
        return unitType;
    }

    /** Builds the table for {@code unitType}; its weight classes, roles, networks and motives start over. */
    public void unitType(int unitType) {
        if (UNIT_TYPES.contains(unitType) && unitType != this.unitType) {
            this.unitType = unitType;
            refreshUnitType();
        }
    }

    /** The weight classes the unit type has, lightest first; none when it has no classes to choose. */
    public List<Integer> weightClasses() {
        return weightClasses;
    }

    /** The message key of {@code weightClass}'s name. */
    public static String weightKey(int weightClass) {
        return "RandomArmyDialog.weight_class_" + weightClass;
    }

    public boolean weight(int weightClass) {
        return weights.contains(weightClass);
    }

    public void weight(int weightClass, boolean chosen) {
        if (!weightClasses.contains(weightClass)) {
            return;
        }
        weights.remove((Integer) weightClass);
        if (chosen) {
            weights.add(weightClass);
            weights.sort(null);
        }
    }

    /** The mission roles that fit the unit type. */
    public List<MissionRole> roles() {
        return offeredRoles;
    }

    /** The message key of {@code role}'s name; its tooltip's is this with ".tooltip". */
    public static String roleKey(MissionRole role) {
        return "MissionRole." + role;
    }

    public boolean role(MissionRole role) {
        return roles.contains(role);
    }

    public void role(MissionRole role, boolean chosen) {
        if (!offeredRoles.contains(role)) {
            return;
        }
        roles.remove(role);
        if (chosen) {
            roles.add(role);
        }
    }

    /** How strictly the roles apply, 1 (Low) to 3 (High). */
    public int strictness() {
        return strictness;
    }

    public void strictness(int strictness) {
        this.strictness = Math.clamp(strictness, 1, STRICTNESS_KEYS.size());
    }

    /** The C3 networks the unit type can require, the first none; empty when it has no networks. */
    public List<Choice<Integer>> networks() {
        return networks;
    }

    public int network() {
        return network;
    }

    public void network(int mask) {
        for (Choice<Integer> choice : networks) {
            if (choice.value() == mask) {
                network = mask;
                return;
            }
        }
    }

    /** The motive types the unit type comes in, by their name for {@link EntityMovementMode#parseFromString}. */
    public List<Choice<String>> motives() {
        List<Choice<String>> offered = new ArrayList<>();
        for (Map.Entry<String, String> entry : motiveKeys.entrySet()) {
            offered.add(new Choice<>(entry.getValue(), entry.getKey()));
        }
        return offered;
    }

    public boolean motive(String name) {
        return Boolean.TRUE.equals(motives.get(name));
    }

    public void motive(String name, boolean chosen) {
        if (motives.containsKey(name)) {
            motives.put(name, chosen);
        }
    }

    /**
     * The table for what is chosen, built when anything changed since it last was; null when no faction is offered.
     * Building one may load the year's data, so it is called off a render thread.
     */
    public @Nullable UnitTable table() {
        FactionRecord record = factions.record();
        if (record == null) {
            request = null;
            table = null;
            return null;
        }
        List<EntityMovementMode> modes = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : motives.entrySet()) {
            if (entry.getValue()) {
                modes.add(EntityMovementMode.parseFromString(entry.getKey()));
            }
        }
        Request asked = new Request(record.getKey(), unitType, factions.year(), factions.rating(), List.copyOf(weights),
              network, List.copyOf(modes), List.copyOf(roles), strictness);
        if (!asked.equals(request)) {
            table = finder.find(record, unitType, asked.year(), asked.rating(), asked.weights(), network, modes,
                  asked.roles(), strictness);
            request = asked;
        }
        return table;
    }

    @Override
    public boolean canDeal() {
        return factions.record() != null;
    }

    @Override
    public List<MekSummary> deal(int count, List<MekSummary> kept) {
        UnitTable built = table();
        return (built == null || built.getNumEntries() == 0) ? List.of() : built.generateUnits(count);
    }

    @Override
    public @Nullable MekSummary replace(MekSummary unit, List<MekSummary> rest) {
        UnitTable built = table();
        return (built == null || built.getNumEntries() == 0) ? null : built.generateUnit();
    }

    @Override
    public @Nullable GenerationContext context() {
        return GenerationContext.of(factions.record(), factions.year(), factions.rating(),
              GenerationContext.Source.RAT_GENERATOR);
    }

    private void refreshUnitType() {
        weightClasses = List.of();
        weights.clear();
        switch (unitType) {
            case UnitType.MEK -> weightClasses(EntityWeightClass.WEIGHT_ULTRA_LIGHT, EntityWeightClass.WEIGHT_COLOSSAL,
                  false);
            case UnitType.NAVAL, UnitType.PROTOMEK -> weightClasses(EntityWeightClass.WEIGHT_LIGHT,
                  EntityWeightClass.WEIGHT_SUPER_HEAVY, true);
            case UnitType.TANK -> weightClasses(EntityWeightClass.WEIGHT_LIGHT, EntityWeightClass.WEIGHT_SUPER_HEAVY,
                  false);
            case UnitType.BATTLE_ARMOR -> weightClasses(EntityWeightClass.WEIGHT_ULTRA_LIGHT,
                  EntityWeightClass.WEIGHT_ASSAULT, true);
            case UnitType.AEROSPACE_FIGHTER -> weightClasses(EntityWeightClass.WEIGHT_LIGHT,
                  EntityWeightClass.WEIGHT_HEAVY, false);
            case UnitType.DROPSHIP -> weightClasses(EntityWeightClass.WEIGHT_SMALL_DROP,
                  EntityWeightClass.WEIGHT_LARGE_DROP, true);
            case UnitType.WARSHIP -> weightClasses(EntityWeightClass.WEIGHT_SMALL_WAR,
                  EntityWeightClass.WEIGHT_LARGE_WAR, true);
            default -> { }
        }

        List<MissionRole> fitting = new ArrayList<>();
        for (MissionRole role : MissionRole.values()) {
            if (role.fitsUnitType(unitType)) {
                fitting.add(role);
            }
        }
        offeredRoles = List.copyOf(fitting);
        roles.retainAll(offeredRoles);

        networks = networksOf(unitType);
        network = ModelRecord.NETWORK_NONE;

        motives.clear();
        motiveKeys.clear();
        switch (unitType) {
            case UnitType.TANK -> {
                offerMotive("hover", true);
                offerMotive("tracked", true);
                offerMotive("wheeled", true);
                offerMotive("wige", true);
                offerMotive("vtol", false);
            }
            case UnitType.INFANTRY -> {
                offerMotive("leg", true);
                offerMotive("jump", true);
                offerMotive("motorized", true);
                offerMotive("hover", "RandomArmyDialog.Mek.hover");
                offerMotive("tracked", "RandomArmyDialog.Mek.tracked");
                offerMotive("wheeled", "RandomArmyDialog.Mek.wheeled");
            }
            case UnitType.BATTLE_ARMOR -> {
                offerMotive("leg", true);
                offerMotive("jump", true);
                offerMotive("umu", true);
                offerMotive("vtol", true);
            }
            case UnitType.NAVAL -> {
                offerMotive("naval", true);
                offerMotive("hydrofoil", true);
                offerMotive("submarine", true);
            }
            case UnitType.DROPSHIP -> {
                offerMotive("aerodyne", true);
                offerMotive("spheroid", true);
            }
            default -> { }
        }
    }

    private void offerMotive(String name, boolean chosen) {
        offerMotive(name, "RandomArmyDialog.Motive." + name);
        motives.put(name, chosen);
    }

    private void offerMotive(String name, String key) {
        motiveKeys.put(name, key);
        motives.put(name, true);
    }

    private void weightClasses(int start, int end, boolean all) {
        List<Integer> offered = new ArrayList<>();
        for (int weightClass = start; weightClass <= end; weightClass++) {
            offered.add(weightClass);
        }
        weightClasses = List.copyOf(offered);
        if (all) {
            weights.addAll(offered);
        } else {
            // The lightest class, skipping ultra-light where the unit type has one, since almost nothing is built so
            weights.add(offered.get(Math.min(start > EntityWeightClass.WEIGHT_ULTRA_LIGHT ? 0 : 1,
                  offered.size() - 1)));
        }
    }

    private static List<Choice<Integer>> networksOf(int unitType) {
        return switch (unitType) {
            case UnitType.MEK, UnitType.TANK, UnitType.VTOL, UnitType.NAVAL, UnitType.CONV_FIGHTER,
                 UnitType.AEROSPACE_FIGHTER -> List.of(
                  new Choice<>("RandomArmyDialog.NoNetwork", ModelRecord.NETWORK_NONE),
                  new Choice<>("RandomArmyDialog.C3S", ModelRecord.NETWORK_C3_SLAVE),
                  new Choice<>("RandomArmyDialog.C3M", ModelRecord.NETWORK_C3_MASTER),
                  new Choice<>("RandomArmyDialog.C3I", ModelRecord.NETWORK_C3I),
                  new Choice<>("RandomArmyDialog.C3SB", ModelRecord.NETWORK_BOOSTED_SLAVE),
                  new Choice<>("RandomArmyDialog.C3MB", ModelRecord.NETWORK_BOOSTED_MASTER),
                  new Choice<>("RandomArmyDialog.C3CC", ModelRecord.NETWORK_COMPANY_COMMAND),
                  new Choice<>("RandomArmyDialog.C3CCB",
                        ModelRecord.NETWORK_COMPANY_COMMAND | ModelRecord.NETWORK_BOOSTED),
                  new Choice<>("RandomArmyDialog.Nova", ModelRecord.NETWORK_NOVA));
            case UnitType.BATTLE_ARMOR -> List.of(
                  new Choice<>("RandomArmyDialog.NoNetwork", ModelRecord.NETWORK_NONE),
                  new Choice<>("RandomArmyDialog.C3BA", ModelRecord.NETWORK_BA_C3),
                  new Choice<>("RandomArmyDialog.C3BAB", ModelRecord.NETWORK_BOOSTED_SLAVE),
                  new Choice<>("RandomArmyDialog.C3I", ModelRecord.NETWORK_C3I));
            case UnitType.DROPSHIP, UnitType.JUMPSHIP, UnitType.WARSHIP, UnitType.SPACE_STATION -> List.of(
                  new Choice<>("RandomArmyDialog.NoNetwork", ModelRecord.NETWORK_NONE),
                  new Choice<>("RandomArmyDialog.C3N", ModelRecord.NETWORK_NAVAL_C3));
            default -> List.of();
        };
    }
}
