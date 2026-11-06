/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntConsumer;

import jakarta.annotation.Nullable;
import megamek.client.ratgenerator.FactionRecord;
import megamek.client.ratgenerator.FormationType;
import megamek.client.ratgenerator.GenerationContext;
import megamek.client.ratgenerator.MissionRole;
import megamek.client.ratgenerator.ModelRecord;
import megamek.client.ratgenerator.Parameters;
import megamek.client.ratgenerator.UnitTable;
import megamek.common.loaders.MekSummary;
import megamek.common.units.EntityMovementMode;
import megamek.common.units.UnitType;

/**
 * A formation the RAT Generator builds for a faction, without a toolkit, as the Swing {@code RandomArmyDialog}'s
 * Formation Builder tab asks for it: a unit type, a formation that allows it, additional units (mechanized battle
 * armor, an air lance or units of another type) and, on the ground, a C3 network. A formation holds together: every
 * roll deals a whole new one, and its cards are not kept or rerolled alone.
 */
public final class FormationForm implements RandomArmyForm.Dealer {

    /** The units that go with the formation. */
    public enum Extra {
        /** None: the formation alone. */
        SIMPLE("RandomArmyDialog.simpleFormation"),
        /** Battle armor riding the formation's omni units. */
        MECHANIZED_BA("RandomArmyDialog.mechBA"),
        /** Two aerospace fighters of one chassis. */
        AIR_LANCE("RandomArmyDialog.airLance"),
        /** Units of another type. */
        OTHER("RandomArmyDialog.otherUnitType");

        private final String key;

        Extra(String key) {
            this.key = key;
        }

        /** The message key of its name. */
        public String key() {
            return key;
        }
    }

    /** Formations of one category: its head, when the category is a formation itself, and its variants. */
    public record Group(String category, @Nullable FormationType head, List<FormationType> members) { }

    /** The unit types a formation can be built of. */
    public static final List<Integer> UNIT_TYPES;
    /** The unit types additional units can be of. */
    public static final List<Integer> OTHER_TYPES;
    /** The most additional units. */
    public static final int MAX_EXTRA = 12;

    static {
        List<Integer> types = new ArrayList<>();
        for (int type : RatGeneratorForm.UNIT_TYPES) {
            if (type < UnitType.JUMPSHIP) {
                types.add(type);
            }
        }
        UNIT_TYPES = List.copyOf(types);
        List<Integer> others = new ArrayList<>();
        for (int type = 0; type < UnitType.JUMPSHIP; type++) {
            if (type != UnitType.GUN_EMPLACEMENT) {
                others.add(type);
            }
        }
        OTHER_TYPES = List.copyOf(others);
    }

    /** The networks a ground formation can require, the first none, as the Swing tab names them. */
    public static final List<RatGeneratorForm.Choice<Integer>> NETWORKS = List.of(
          new RatGeneratorForm.Choice<>("ForceGenerationOptions.Optional", ModelRecord.NETWORK_NONE),
          new RatGeneratorForm.Choice<>("ForceGenerationOptions.C3Lance", ModelRecord.NETWORK_C3_MASTER),
          new RatGeneratorForm.Choice<>("ForceGenerationOptions.C3Command",
                ModelRecord.NETWORK_C3_MASTER | ModelRecord.NETWORK_COMPANY_COMMAND),
          new RatGeneratorForm.Choice<>("ForceGenerationOptions.C3i", ModelRecord.NETWORK_C3I),
          new RatGeneratorForm.Choice<>("ForceGenerationOptions.C3BLance", ModelRecord.NETWORK_BOOSTED_MASTER),
          new RatGeneratorForm.Choice<>("ForceGenerationOptions.C3BCommand",
                ModelRecord.NETWORK_BOOSTED_MASTER | ModelRecord.NETWORK_COMPANY_COMMAND),
          new RatGeneratorForm.Choice<>("ForceGenerationOptions.Nova", ModelRecord.NETWORK_NOVA));

    private final FactionChoice factions;
    private final List<FormationType> formations;
    private final IntConsumer loader;
    private int unitType = UnitType.MEK;
    private List<Group> groups = List.of();
    private @Nullable FormationType formation;
    private Extra extra = Extra.SIMPLE;
    private int otherType = UnitType.TANK;
    private int extraCount;
    private int network = ModelRecord.NETWORK_NONE;
    private boolean empty;

    /**
     * @param formations every formation there is, {@link FormationType#getAllFormations()}
     * @param loader     loads the RAT Generator's data for a year before a formation is built of it
     */
    public FormationForm(FactionChoice factions, List<FormationType> formations, IntConsumer loader) {
        this.factions = Objects.requireNonNull(factions);
        this.formations = List.copyOf(formations);
        this.loader = Objects.requireNonNull(loader);
        refreshGroups();
    }

    public FactionChoice factions() {
        return factions;
    }

    public int unitType() {
        return unitType;
    }

    /** Builds the formation of {@code unitType}: air formations for fighters and up, ground ones below. */
    public void unitType(int unitType) {
        if (UNIT_TYPES.contains(unitType) && unitType != this.unitType) {
            this.unitType = unitType;
            refreshGroups();
        }
    }

    /** Whether the formations offered are ground ones; air ones are for fighters and up. */
    public boolean ground() {
        return unitType < UnitType.CONV_FIGHTER;
    }

    /** The formations offered for the unit type, by category, both in name order. */
    public List<Group> groups() {
        return groups;
    }

    public @Nullable FormationType formation() {
        return formation;
    }

    /** Chooses the formation named {@code name} when it is offered and allows the unit type. */
    public void formation(String name) {
        for (Group group : groups) {
            if (group.head() != null && group.head().getName().equals(name) && allowed(group.head())) {
                formation = group.head();
                return;
            }
            for (FormationType member : group.members()) {
                if (member.getName().equals(name) && allowed(member)) {
                    formation = member;
                    return;
                }
            }
        }
    }

    /** Whether {@code formation} can be built of the unit type. */
    public boolean allowed(FormationType formation) {
        return formation.isAllowedUnitType(unitType);
    }

    public Extra extra() {
        return extra;
    }

    public void extra(Extra extra) {
        this.extra = Objects.requireNonNull(extra);
    }

    /** The unit type of {@link Extra#OTHER} units. */
    public int otherType() {
        return otherType;
    }

    public void otherType(int otherType) {
        if (OTHER_TYPES.contains(otherType)) {
            this.otherType = otherType;
        }
    }

    /** How many additional units go with the formation; none goes for the formation alone. */
    public int extraCount() {
        return extraCount;
    }

    public void extraCount(int count) {
        extraCount = Math.clamp(count, 0, MAX_EXTRA);
    }

    /** The networks offered: on the ground only. */
    public List<RatGeneratorForm.Choice<Integer>> networks() {
        return ground() ? NETWORKS : List.of();
    }

    public int network() {
        return ground() ? network : ModelRecord.NETWORK_NONE;
    }

    public void network(int mask) {
        for (RatGeneratorForm.Choice<Integer> choice : NETWORKS) {
            if (choice.value() == mask) {
                network = mask;
                return;
            }
        }
    }

    /** Whether the last roll could build no formation with what is chosen. */
    public boolean empty() {
        return empty;
    }

    @Override
    public boolean canDeal() {
        return factions.record() != null && formation != null;
    }

    @Override
    public boolean wholeHand() {
        return true;
    }

    /**
     * Builds the formation of {@code count} units, with its additional units, as the Swing tab does. Building one
     * loads tables, so it is called off a render thread.
     */
    @Override
    public List<MekSummary> deal(int count, List<MekSummary> kept) {
        List<MekSummary> units = build(count);
        empty = units.isEmpty();
        return units;
    }

    private List<MekSummary> build(int count) {
        FactionRecord record = factions.record();
        if (record == null || formation == null) {
            return List.of();
        }
        loader.accept(factions.year());
        List<Parameters> params = new ArrayList<>();
        params.add(parameters(record, unitType, EnumSet.noneOf(MissionRole.class)));
        List<Integer> numUnits = new ArrayList<>();
        numUnits.add(count);
        if (extraCount > 0) {
            if (extra == Extra.OTHER) {
                params.add(parameters(record, otherType, EnumSet.noneOf(MissionRole.class)));
                numUnits.add(extraCount);
            } else if (extra == Extra.MECHANIZED_BA) {
                // As many of the formation's units as there are battle armor squads carry them, so are omnis
                numUnits.set(0, Math.min(extraCount, count));
                if (count > extraCount) {
                    params.add(params.get(0).copy());
                    numUnits.add(count - extraCount);
                }
                params.get(0).getRoles().add(MissionRole.MECHANIZED_BA);
            }
        }

        List<MekSummary> units = new ArrayList<>(formation.generateFormation(params, numUnits, network(), false));
        if (units.isEmpty() || extraCount == 0) {
            return units;
        }
        if (extra == Extra.MECHANIZED_BA) {
            // The battle armor do not count for the formation's rules: a formation of their own, else any
            Parameters ba = parameters(record, UnitType.BATTLE_ARMOR, EnumSet.of(MissionRole.MECHANIZED_BA));
            List<MekSummary> squads = formation.generateFormation(ba, extraCount, ModelRecord.NETWORK_NONE, true);
            if (squads.isEmpty()) {
                squads = UnitTable.findTable(ba).generateUnits(extraCount);
            }
            units.addAll(squads);
        } else if (extra == Extra.AIR_LANCE) {
            UnitTable fighters = UnitTable.findTable(parameters(record, UnitType.AEROSPACE_FIGHTER,
                  EnumSet.noneOf(MissionRole.class)));
            MekSummary first = fighters.generateUnit();
            if (first != null) {
                units.add(first);
                MekSummary second = fighters.generateUnit(unit -> unit.getChassis().equals(first.getChassis()));
                units.add(Objects.requireNonNullElse(second, first));
            }
        }
        return units;
    }

    private Parameters parameters(FactionRecord record, int type, EnumSet<MissionRole> roles) {
        return new Parameters(record, type, factions.year(), factions.rating(), null, ModelRecord.NETWORK_NONE,
              EnumSet.noneOf(EntityMovementMode.class), roles, 0, record);
    }

    @Override
    public @Nullable MekSummary replace(MekSummary unit, List<MekSummary> rest) {
        return null;
    }

    @Override
    public @Nullable GenerationContext context() {
        return GenerationContext.of(factions.record(), factions.year(), factions.rating(),
              GenerationContext.Source.FORMATION_BUILDER);
    }

    private void refreshGroups() {
        boolean ground = ground();
        Map<String, TreeSet<String>> byCategory = new TreeMap<>();
        Map<String, FormationType> byName = new TreeMap<>();
        for (FormationType type : formations) {
            if (type.isGround() == ground) {
                byCategory.computeIfAbsent(type.getCategory(), category -> new TreeSet<>()).add(type.getName());
                byName.put(type.getName(), type);
            }
        }
        List<Group> offered = new ArrayList<>();
        for (Map.Entry<String, TreeSet<String>> entry : byCategory.entrySet()) {
            String category = entry.getKey();
            FormationType head = entry.getValue().remove(category) ? byName.get(category) : null;
            List<FormationType> members = new ArrayList<>();
            for (String name : entry.getValue()) {
                members.add(byName.get(name));
            }
            offered.add(new Group(category, head, List.copyOf(members)));
        }
        groups = List.copyOf(offered);

        if (formation == null || formation.isGround() != ground || !allowed(formation)) {
            formation = firstAllowed();
        }
    }

    private @Nullable FormationType firstAllowed() {
        for (Group group : groups) {
            if (group.head() != null && allowed(group.head())) {
                return group.head();
            }
            for (FormationType member : group.members()) {
                if (allowed(member)) {
                    return member;
                }
            }
        }
        return null;
    }
}
