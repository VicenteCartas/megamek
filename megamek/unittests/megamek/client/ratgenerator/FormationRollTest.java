/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ratgenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import megamek.common.loaders.MekSummary;
import megamek.common.units.UnitType;
import org.junit.jupiter.api.Test;

class FormationRollTest {

    private static final FactionRecord FACTION = new FactionRecord("FS", "Federated Suns");

    private static FormationRoll.Request request(int count, FormationRoll.Extra extra, int extraCount) {
        return new FormationRoll.Request(FACTION, UnitType.MEK, 3050, "B", count, extra, UnitType.TANK, extraCount,
              ModelRecord.NETWORK_C3_MASTER);
    }

    private static MekSummary unit(String chassis) {
        MekSummary unit = new MekSummary();
        unit.setChassis(chassis);
        return unit;
    }

    @Test
    void parametersCarryTheRequestsFactionYearAndRating() {
        Parameters params = FormationRoll.parameters(request(4, FormationRoll.Extra.NONE, 0), UnitType.TANK,
              EnumSet.of(MissionRole.RECON));

        assertSame(FACTION, params.getFaction());
        assertSame(FACTION, params.getDeployingFaction());
        assertEquals(UnitType.TANK, params.getUnitType());
        assertEquals(3050, params.getYear());
        assertEquals("B", params.getRating());
        assertTrue(params.getWeightClasses().isEmpty());
        assertEquals(ModelRecord.NETWORK_NONE, params.getNetworkMask());
        assertEquals(Set.of(MissionRole.RECON), Set.copyOf(params.getRoles()));
        assertEquals(0, params.getRoleStrictness());
    }

    @Test
    void formationAloneHasOnePart() {
        FormationRoll.Plan plan = FormationRoll.plan(request(4, FormationRoll.Extra.AIR_LANCE, 2));

        assertEquals(1, plan.parameters().size());
        assertEquals(List.of(4), plan.numUnits());
        assertEquals(UnitType.MEK, plan.parameters().getFirst().getUnitType());
        assertTrue(plan.parameters().getFirst().getRoles().isEmpty());
    }

    @Test
    void otherUnitsAreASecondPartOfTheirType() {
        FormationRoll.Plan plan = FormationRoll.plan(request(4, FormationRoll.Extra.OTHER, 3));

        assertEquals(List.of(4, 3), plan.numUnits());
        assertEquals(UnitType.MEK, plan.parameters().get(0).getUnitType());
        assertEquals(UnitType.TANK, plan.parameters().get(1).getUnitType());
    }

    @Test
    void noAdditionalUnitsIgnoresTheExtra() {
        FormationRoll.Plan plan = FormationRoll.plan(request(4, FormationRoll.Extra.OTHER, 0));

        assertEquals(List.of(4), plan.numUnits());
    }

    @Test
    void mechanizedBattleArmorSplitsOffTheCarryingOmnis() {
        FormationRoll.Plan plan = FormationRoll.plan(request(4, FormationRoll.Extra.MECHANIZED_BA, 3));

        assertEquals(List.of(3, 1), plan.numUnits());
        assertTrue(plan.parameters().get(1).getRoles().isEmpty());
        assertEquals(UnitType.MEK, plan.parameters().get(1).getUnitType());
    }

    @Test
    void mechanizedBattleArmorOutnumberingTheFormationMakesItAllOmnis() {
        FormationRoll.Plan plan = FormationRoll.plan(request(4, FormationRoll.Extra.MECHANIZED_BA, 6));

        assertEquals(List.of(4), plan.numUnits());
        assertEquals(1, plan.parameters().size());
    }

    @Test
    void fighterPairSharesTheFirstsChassis() {
        MekSummary first = unit("Stuka");
        MekSummary other = unit("Sparrowhawk");
        MekSummary match = unit("Stuka");

        List<MekSummary> pair = FormationRoll.pairOfOneChassis(() -> first,
              filter -> filter.include(other) ? other : filter.include(match) ? match : null);

        assertEquals(List.of(first, match), pair);
    }

    @Test
    void fighterPairRepeatsTheFirstWhenNoneMatches() {
        MekSummary first = unit("Stuka");

        assertEquals(List.of(first, first), FormationRoll.pairOfOneChassis(() -> first, filter -> null));
    }

    @Test
    void noFirstFighterMeansNoPair() {
        assertTrue(FormationRoll.pairOfOneChassis(() -> null, filter -> {
            throw new AssertionError("no second roll without a first");
        }).isEmpty());
    }

    @Test
    void requestRequiresAnExtra() {
        assertThrows(NullPointerException.class, () -> request(4, null, 0));
    }
}
