/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;

import megamek.client.ratgenerator.FactionRecord;
import megamek.client.ratgenerator.MissionRole;
import megamek.client.ratgenerator.ModelRecord;
import megamek.common.alphaStrike.ASUnitType;
import megamek.common.loaders.MekSummary;
import megamek.common.units.EntityWeightClass;
import megamek.common.units.UnitType;
import org.junit.jupiter.api.Test;

/** The random army's generators, without the RAT Generator's data or a toolkit. */
class ArmyGeneratorsTest {

    private static MekSummary unit(String name, ASUnitType type, int bv, int year) {
        MekSummary unit = new MekSummary();
        unit.setName(name);
        unit.setChassis(name);
        unit.setModel("");
        unit.setAsUnitType(type);
        unit.setUnitType(UnitType.getTypeName(type == ASUnitType.CV ? UnitType.TANK : UnitType.MEK));
        unit.setBV(bv);
        unit.setTons(50);
        unit.setYear(year);
        return unit;
    }

    private static FactionRecord faction(String key, String years, boolean minor, String ratings)
          throws ParseException {
        FactionRecord faction = new FactionRecord(key, key + " name");
        faction.setYears(years);
        faction.setMinor(minor);
        faction.setRatings(ratings);
        return faction;
    }

    // ---- Battle Value ----

    @Test
    void theBattleValueArmyCountsItsUnitsByKindAndYears() {
        MekSummary[] roster = { unit("Old", ASUnitType.BM, 1000, 2900), unit("Mek", ASUnitType.BM, 1000, 3040),
              unit("Tank", ASUnitType.CV, 500, 3040) };
        BattleValueArmy army = new BattleValueArmy(() -> roster, 3050);
        assertEquals(3000, army.minYear(), "half a century before the game, as the Swing tab");
        assertEquals(3050, army.maxYear());
        assertEquals(1, army.available(BattleValueArmy.Kind.MEK), "a design too old is left out");
        assertEquals(1, army.available(BattleValueArmy.Kind.VEHICLE));
        army.count(BattleValueArmy.Kind.BATTLE_ARMOR, 2);
        assertTrue(army.shortOf(BattleValueArmy.Kind.BATTLE_ARMOR), "asked for battle armor there is none of");
        assertFalse(army.usesCount());
        assertEquals(6, army.size());
        army.years(2800, 3050);
        assertEquals(2, army.available(BattleValueArmy.Kind.MEK), "the years reach the old design now");
        army.filter(unit -> !unit.getName().equals("Mek"));
        assertEquals(1, army.available(BattleValueArmy.Kind.MEK), "the filter narrows the units");
        army.count(BattleValueArmy.Kind.MEK, 99);
        assertEquals(BattleValueArmy.MAX_COUNT, army.count(BattleValueArmy.Kind.MEK));
    }

    @Test
    void theBattleValueBudgetIsARange() {
        BattleValueArmy army = new BattleValueArmy(() -> new MekSummary[0], 3050);
        army.bv(9000, 5000);
        assertEquals(5000, army.maxBV());
        assertEquals(5000, army.minBV(), "a minimum over the maximum is the maximum");
        army.bv(-5, BattleValueArmy.MAX_BV * 2);
        assertEquals(0, army.minBV());
        assertEquals(BattleValueArmy.MAX_BV, army.maxBV());
    }

    @Test
    void theBattleValueArmyDealsWithinItsBudget() {
        MekSummary[] roster = new MekSummary[8];
        for (int i = 0; i < roster.length; i++) {
            roster[i] = unit("Mek " + i, ASUnitType.BM, 1500, 3040);
        }
        BattleValueArmy army = new BattleValueArmy(() -> roster, 3050);
        army.count(BattleValueArmy.Kind.MEK, 3);
        List<MekSummary> dealt = army.deal(0, List.of());
        assertEquals(3, dealt.size());
        List<MekSummary> rest = army.deal(0, List.of(dealt.get(0)));
        assertEquals(2, rest.size(), "a kept unit counts toward its kind");
    }

    // ---- Balanced ----

    @Test
    void theBalancedLanceKeepsItsTargetsPlaceAcrossMeasures() {
        BalancedArmy army = new BalancedArmy(() -> new MekSummary[0]);
        assertEquals(BalancedArmy.BalanceBy.BV, army.balanceBy());
        army.target(BalancedArmy.BalanceBy.BV.targets()[2]);
        army.balanceBy(BalancedArmy.BalanceBy.TONS);
        assertEquals(BalancedArmy.BalanceBy.TONS.targets()[2], army.target(), "the target at the same place");
        army.tolerance(150);
        assertEquals(100, army.tolerance());
        assertFalse(army.canDeal(), "no units, nothing to deal");
    }

    @Test
    void theBalancedLanceMakesItsTarget() {
        MekSummary[] roster = new MekSummary[6];
        for (int i = 0; i < roster.length; i++) {
            roster[i] = unit("Mek " + i, ASUnitType.BM, 2000, 3040);
        }
        BalancedArmy army = new BalancedArmy(() -> roster);
        army.target(4000);
        List<MekSummary> dealt = army.deal(2, List.of());
        assertEquals(2, dealt.size());
        assertEquals(4000, army.strength(dealt));
        army.filter(unit -> unit.getName().equals("Mek 0"));
        assertEquals(1, army.available(), "the filter narrows the units");
    }

    // ---- Faction ----

    @Test
    void theFactionsAreThoseActiveInTheYear() throws ParseException {
        FactionRecord inner = faction("IS", "2398-", false, "");
        FactionRecord davion = faction("FS", "3028-3067", false, "F,D,C,B,A");
        FactionRecord minor = faction("Minor", "3000-", true, "");
        FactionRecord command = faction("FS.DM", "3028-", false, "");
        FactionChoice choice = new FactionChoice(List.of(inner, davion, minor, command), 3050);
        assertEquals(List.of(davion, inner), choice.factions(), "major ones by name, not commands");
        assertEquals(inner, choice.faction(), "the Inner Sphere at large first");
        choice.showMinor(true);
        assertTrue(choice.factions().contains(minor));
        choice.faction("FS");
        assertEquals(List.of(command), choice.commands());
        assertEquals(List.of("A", "B", "C", "D", "F"), choice.ratings(), "best first");
        assertEquals("A", choice.rating());
        choice.rating("C");
        choice.command("FS.DM");
        assertEquals("FS.DM", choice.key(), "the command's table");
        choice.command(null);
        assertEquals("FS", choice.key(), "the faction's general command");
        choice.year(3100);
        assertEquals(inner, choice.faction(), "a faction gone by the year gives way to the Inner Sphere");
        choice.year(1000);
        assertEquals(FactionChoice.EARLIEST_YEAR, choice.year());
    }

    @Test
    void theTableIsBuiltOnceForTheSameChoices() throws ParseException {
        FactionChoice choice = new FactionChoice(List.of(faction("IS", "2398-", false, "")), 3050);
        List<String> asked = new ArrayList<>();
        RatGeneratorForm form = new RatGeneratorForm(choice,
              (faction, unitType, year, rating, weights, network, motives, roles, strictness) -> {
                  asked.add(faction.getKey() + " " + unitType + " " + weights + " " + roles + " " + strictness);
                  return null;
              });
        assertTrue(form.canDeal());
        assertEquals(List.of(EntityWeightClass.WEIGHT_LIGHT), weightsChosen(form), "the lightest Mek class");
        assertNull(form.table());
        assertNull(form.table());
        assertEquals(1, asked.size(), "the same choices build no new table");
        form.weight(EntityWeightClass.WEIGHT_HEAVY, true);
        MissionRole role = form.roles().get(0);
        form.role(role, true);
        form.strictness(9);
        form.table();
        assertEquals(2, asked.size());
        assertEquals("IS " + UnitType.MEK + " " + List.of(EntityWeightClass.WEIGHT_LIGHT,
              EntityWeightClass.WEIGHT_HEAVY) + " " + List.of(role) + " 3", asked.get(1));
        assertTrue(form.deal(4, List.of()).isEmpty(), "no table, no units");
    }

    @Test
    void theUnitTypeSetsItsOwnChoices() throws ParseException {
        RatGeneratorForm form = new RatGeneratorForm(
              new FactionChoice(List.of(faction("IS", "2398-", false, "")), 3050),
              (faction, unitType, year, rating, weights, network, motives, roles, strictness) -> null);
        form.unitType(UnitType.TANK);
        assertEquals(List.of(EntityWeightClass.WEIGHT_LIGHT), weightsChosen(form), "the lightest tank class");
        assertTrue(form.motive("hover"));
        assertFalse(form.motive("vtol"), "a VTOL is a tank only when asked for");
        form.network(ModelRecord.NETWORK_C3I);
        assertEquals(ModelRecord.NETWORK_C3I, form.network());
        form.unitType(UnitType.INFANTRY);
        assertTrue(form.networks().isEmpty());
        assertEquals(ModelRecord.NETWORK_NONE, form.network(), "a new unit type starts over");
        form.unitType(-1);
        assertEquals(UnitType.INFANTRY, form.unitType(), "a type not offered is not taken");
    }

    private static List<Integer> weightsChosen(RatGeneratorForm form) {
        List<Integer> chosen = new ArrayList<>();
        for (int weight : form.weightClasses()) {
            if (form.weight(weight)) {
                chosen.add(weight);
            }
        }
        return chosen;
    }

    // ---- Formation ----

    @Test
    void theFormationDealsAWholeHandOnTheGroundsChoices() throws ParseException {
        FormationForm form = new FormationForm(new FactionChoice(List.of(faction("IS", "2398-", false, "")), 3050),
              List.of(), year -> { });
        assertTrue(form.wholeHand());
        assertFalse(form.canDeal(), "no formation to build");
        assertTrue(form.ground());
        assertEquals(FormationForm.NETWORKS, form.networks());
        form.network(ModelRecord.NETWORK_NOVA);
        assertEquals(ModelRecord.NETWORK_NOVA, form.network());
        form.unitType(UnitType.AEROSPACE_FIGHTER);
        assertFalse(form.ground());
        assertTrue(form.networks().isEmpty(), "networks are for the ground");
        assertEquals(ModelRecord.NETWORK_NONE, form.network());
        form.extraCount(99);
        assertEquals(FormationForm.MAX_EXTRA, form.extraCount());
        form.otherType(UnitType.GUN_EMPLACEMENT);
        assertEquals(UnitType.TANK, form.otherType(), "gun emplacements are not additional units");
    }
}
