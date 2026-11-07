/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ratgenerator;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

import jakarta.annotation.Nullable;
import megamek.common.loaders.MekSummary;
import megamek.common.units.EntityMovementMode;
import megamek.common.units.UnitType;

/**
 * Builds one formation and its additional units, free of any toolkit so the Swing Formation Builder tab and the libGDX
 * lobby's formation form roll the same way: the formation's parameters, mechanized battle armor riding its omni units,
 * an air lance of one chassis, or units of another type.
 */
public final class FormationRoll {

    /** The units that go with the formation. */
    public enum Extra {
        /** None: the formation alone. */
        NONE,
        /** Units of {@link Request#otherUnitType()}. */
        OTHER,
        /** Battle armor riding the formation's omni units, as a formation of their own when they can be. */
        MECHANIZED_BA,
        /** Two aerospace fighters of one chassis. */
        AIR_LANCE
    }

    /**
     * What to build.
     *
     * @param faction       the faction the formation belongs to
     * @param unitType      the unit type of the formation
     * @param year          the game year
     * @param rating        the equipment rating, or {@code null} for none
     * @param count         how many units the formation has
     * @param extra         the units that go with the formation
     * @param otherUnitType the unit type of {@link Extra#OTHER} units
     * @param extraCount    how many additional units; none builds the formation alone
     * @param network       the C3 network the formation requires, {@link ModelRecord#NETWORK_NONE} for none
     */
    public record Request(FactionRecord faction, int unitType, int year, @Nullable String rating, int count,
          Extra extra, int otherUnitType, int extraCount, int network) {
        public Request {
            Objects.requireNonNull(extra);
        }
    }

    /** The parameters of each part of the formation and how many units each part has. */
    public record Plan(List<Parameters> parameters, List<Integer> numUnits) { }

    private FormationRoll() {
    }

    /**
     * Builds the formation {@code formation} as {@code request} asks, followed by its additional units.
     *
     * @return the units built, empty when no formation could be built; the list may be changed
     */
    public static List<MekSummary> generate(FormationType formation, Request request) {
        Plan plan = plan(request);
        List<MekSummary> units = new ArrayList<>(formation.generateFormation(plan.parameters(), plan.numUnits(),
              request.network(), false));
        if (units.isEmpty() || request.extraCount() <= 0) {
            return units;
        }
        if (request.extra() == Extra.MECHANIZED_BA) {
            // The battle armor do not count for the formation's rules: a formation of their own, else any
            Parameters ba = parameters(request, UnitType.BATTLE_ARMOR, EnumSet.of(MissionRole.MECHANIZED_BA));
            List<MekSummary> squads = formation.generateFormation(ba, request.extraCount(), ModelRecord.NETWORK_NONE,
                  true);
            if (squads.isEmpty()) {
                squads = UnitTable.findTable(ba).generateUnits(request.extraCount());
            }
            units.addAll(squads);
        } else if (request.extra() == Extra.AIR_LANCE) {
            UnitTable fighters = UnitTable.findTable(parameters(request, UnitType.AEROSPACE_FIGHTER,
                  EnumSet.noneOf(MissionRole.class)));
            units.addAll(pairOfOneChassis(fighters::generateUnit, fighters::generateUnit));
        }
        return units;
    }

    /**
     * The parameters of the formation's parts: the formation itself, then either the units of another type or, for
     * mechanized battle armor, the formation split into the omni units carrying them and the rest.
     */
    public static Plan plan(Request request) {
        List<Parameters> params = new ArrayList<>();
        params.add(parameters(request, request.unitType(), EnumSet.noneOf(MissionRole.class)));
        List<Integer> numUnits = new ArrayList<>();
        numUnits.add(request.count());
        int extraCount = request.extraCount();
        if (extraCount > 0) {
            if (request.extra() == Extra.OTHER) {
                params.add(parameters(request, request.otherUnitType(), EnumSet.noneOf(MissionRole.class)));
                numUnits.add(extraCount);
            } else if (request.extra() == Extra.MECHANIZED_BA) {
                // As many of the formation's units as there are battle armor squads carry them, so are omnis
                numUnits.set(0, Math.min(extraCount, request.count()));
                if (request.count() > extraCount) {
                    params.add(params.getFirst().copy());
                    numUnits.add(request.count() - extraCount);
                }
                params.getFirst().getRoles().add(MissionRole.MECHANIZED_BA);
            }
        }
        return new Plan(params, numUnits);
    }

    /** The parameters of one table of {@code unitType} for the request's faction, year and rating. */
    public static Parameters parameters(Request request, int unitType, EnumSet<MissionRole> roles) {
        return new Parameters(request.faction(), unitType, request.year(), request.rating(), null,
              ModelRecord.NETWORK_NONE, EnumSet.noneOf(EntityMovementMode.class), roles, 0, request.faction());
    }

    /**
     * Two units of one chassis: a first unit, then one matching its chassis, or the first again when none does.
     *
     * @param first    rolls the first unit
     * @param matching rolls a unit the filter accepts
     *
     * @return the pair, empty when no first unit could be rolled
     */
    static List<MekSummary> pairOfOneChassis(Supplier<MekSummary> first,
          Function<UnitTable.UnitFilter, MekSummary> matching) {
        MekSummary unit = first.get();
        if (unit == null) {
            return List.of();
        }
        MekSummary second = matching.apply(ms -> ms.getChassis().equals(unit.getChassis()));
        return List.of(unit, Objects.requireNonNullElse(second, unit));
    }
}
