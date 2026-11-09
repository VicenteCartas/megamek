/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import megamek.client.lobby.RequisitionCatalog.Category;
import megamek.client.lobby.RequisitionCatalog.Filters;
import megamek.client.lobby.RequisitionCatalog.Rules;
import megamek.client.lobby.RequisitionSearch.Query;
import megamek.client.lobby.RequisitionSearch.Result;
import megamek.client.ui.TableSort;
import megamek.client.ui.TableSort.Direction;
import megamek.common.SimpleTechLevel;
import megamek.common.TechConstants;
import megamek.common.loaders.MekSummary;
import megamek.common.units.UnitRole;
import megamek.common.units.UnitType;
import org.junit.jupiter.api.Test;

class RequisitionSortTest {

    private static MekSummary unit(String chassis, int bv, int pv, double tons, int year) {
        MekSummary unit = RequisitionCatalogTest.unit(chassis, "", UnitType.MEK, year, TechConstants.T_INTRO_BOX_SET);
        unit.setBV(bv);
        unit.setPointValue(pv);
        unit.setTons(tons);
        return unit;
    }

    private static List<String> names(List<RequisitionRow> rows, TableSort<RequisitionSort> sort) {
        return names(rows, sort, false);
    }

    private static List<String> names(List<RequisitionRow> rows, TableSort<RequisitionSort> sort, boolean pv) {
        return RequisitionSort.sorted(rows, sort, pv).stream().map(RequisitionRow::name).toList();
    }

    /** The rows of {@code units} in the catalogue's name order, as the search hands them to the sort. */
    private static List<RequisitionRow> rows(MekSummary... units) {
        RequisitionCatalog.Index index = RequisitionCatalog.index(units, unit -> false, unit -> null);
        List<RequisitionRow> rows = new ArrayList<>();
        for (MekSummary unit : RequisitionCatalog.offer(index, Rules.OPEN, Category.ALL, Filters.NONE, "")) {
            rows.add(RequisitionRow.of(unit));
        }
        assertEquals(units.length, rows.size(), "the catalogue offers every unit");
        return rows;
    }

    private static TableSort<RequisitionSort> up(RequisitionSort key) {
        return new TableSort<>(key, Direction.ASCENDING);
    }

    private static TableSort<RequisitionSort> down(RequisitionSort key) {
        return new TableSort<>(key, Direction.DESCENDING);
    }

    @Test
    void numbersStartHighFirstAndTextFromAToZ() {
        for (RequisitionSort key : RequisitionSort.values()) {
            boolean text = (key == RequisitionSort.NAME) || (key == RequisitionSort.ROLE)
                  || (key == RequisitionSort.LEVEL) || (key == RequisitionSort.TYPE);
            assertEquals(text ? Direction.ASCENDING : Direction.DESCENDING, key.defaultDirection(), key.name());
        }
        assertEquals(up(RequisitionSort.NAME), RequisitionSort.BY_NAME);
    }

    @Test
    void everyColumnAndValueHasItsKey() {
        assertEquals(RequisitionSort.MOVE, RequisitionSort.of(RequisitionRow.Column.MOVE));
        assertEquals(RequisitionSort.ARMOR, RequisitionSort.of(RequisitionRow.Column.ARMOR));
        assertEquals(RequisitionSort.ROLE, RequisitionSort.of(RequisitionRow.Column.ROLE));
        assertEquals(RequisitionSort.LEVEL, RequisitionSort.of(RequisitionRow.Column.LEVEL));
        assertEquals("Lobby.requisition.bv", RequisitionSort.VALUE.messageKey(false));
        assertEquals("Lobby.requisition.pv", RequisitionSort.VALUE.messageKey(true), "the value is named as shown");
        assertEquals(RequisitionSort.TONS.messageKey(), RequisitionSort.TONS.messageKey(true));
        for (RequisitionRow.Column column : RequisitionRow.Column.values()) {
            assertEquals(column.messageKey(), RequisitionSort.of(column).messageKey(), "a column's key reads its name");
        }
    }

    @Test
    void theNameOrderIsTheCatalogueOrderAndComesBackUntouched() {
        List<RequisitionRow> rows = rows(unit("'Astrolux'", 1, 1, 1, 3025), unit("Atlas", 1, 1, 1, 3025),
              unit("archer", 1, 1, 1, 3025));
        assertSame(rows, RequisitionSort.sorted(rows, RequisitionSort.BY_NAME, false));
        assertEquals(List.of("archer", "'Astrolux'", "Atlas"), names(rows, RequisitionSort.BY_NAME));
        assertEquals(List.of("Atlas", "'Astrolux'", "archer"), names(rows, down(RequisitionSort.NAME)));
    }

    @Test
    void valuesTonsAndYearsSortAsNumbers() {
        List<RequisitionRow> rows = rows(unit("Atlas", 1897, 52, 100, 2755), unit("Locust", 432, 18, 20, 2499),
              unit("Archer", 1817, 38, 70, 2458), unit("Ostroc", 1176, 64, 60, 2763));
        assertEquals(List.of("Atlas", "Archer", "Ostroc", "Locust"), names(rows, down(RequisitionSort.VALUE)));
        assertEquals(List.of("Locust", "Ostroc", "Archer", "Atlas"), names(rows, up(RequisitionSort.VALUE)));
        assertEquals(List.of("Ostroc", "Atlas", "Archer", "Locust"), names(rows, down(RequisitionSort.VALUE), true),
              "the value sorts as the Point Value while that shows");
        assertEquals(List.of("Locust", "Ostroc", "Archer", "Atlas"), names(rows, up(RequisitionSort.TONS)));
        assertEquals(List.of("Ostroc", "Atlas", "Locust", "Archer"), names(rows, down(RequisitionSort.YEAR)));
    }

    @Test
    void movementSortsByWalkThenRunThenJump() {
        MekSummary fast = unit("Fast", 1, 1, 1, 3025);
        fast.setWalkMp(6);
        fast.setRunMp(9);
        MekSummary jumper = unit("Jumper", 1, 1, 1, 3025);
        jumper.setWalkMp(5);
        jumper.setRunMp(8);
        jumper.setJumpMp(5);
        MekSummary runner = unit("Runner", 1, 1, 1, 3025);
        runner.setWalkMp(5);
        runner.setRunMp(9);
        MekSummary walker = unit("Walker", 1, 1, 1, 3025);
        walker.setWalkMp(5);
        walker.setRunMp(8);
        List<RequisitionRow> rows = rows(walker, runner, jumper, fast);
        assertEquals(List.of("Fast", "Runner", "Jumper", "Walker"), names(rows, down(RequisitionSort.MOVE)));
        assertEquals(List.of("Walker", "Jumper", "Runner", "Fast"), names(rows, up(RequisitionSort.MOVE)));
    }

    @Test
    void armorSortsAsANumberAndUnitsWithoutArmorGoLast() {
        MekSummary heavy = unit("Heavy", 1, 1, 1, 3025);
        heavy.setTotalArmor(1200);
        MekSummary light = unit("Light", 1, 1, 1, 3025);
        light.setTotalArmor(96);
        MekSummary none = unit("Bare", 1, 1, 1, 3025);
        List<RequisitionRow> rows = rows(heavy, light, none);
        assertEquals(List.of("Heavy", "Light", "Bare"), names(rows, down(RequisitionSort.ARMOR)));
        assertEquals(List.of("Light", "Heavy", "Bare"), names(rows, up(RequisitionSort.ARMOR)));
    }

    @Test
    void rolesSortByNameAndUnitsWithoutARoleGoLast() {
        MekSummary sniper = unit("Sniper", 1, 1, 1, 3025);
        sniper.setUnitRole(UnitRole.SNIPER);
        MekSummary brawler = unit("Brawler", 1, 1, 1, 3025);
        brawler.setUnitRole(UnitRole.BRAWLER);
        MekSummary none = unit("Aimless", 1, 1, 1, 3025);
        List<RequisitionRow> rows = rows(sniper, brawler, none);
        Comparator<String> byRole = String.CASE_INSENSITIVE_ORDER;
        List<String> roles = List.of(UnitRole.SNIPER.toString(), UnitRole.BRAWLER.toString());
        List<String> expected = byRole.compare(roles.get(0), roles.get(1)) < 0 ? List.of("Sniper", "Brawler")
              : List.of("Brawler", "Sniper");
        List<String> ascending = new ArrayList<>(expected);
        ascending.add("Aimless");
        List<String> descending = new ArrayList<>(expected.reversed());
        descending.add("Aimless");
        assertEquals(ascending, names(rows, up(RequisitionSort.ROLE)));
        assertEquals(descending, names(rows, down(RequisitionSort.ROLE)));
    }

    @Test
    void rulesLevelsSortFromIntroductoryToUnofficialAndNoneGoesLast() {
        MekSummary intro = level(unit("Intro", 1, 1, 1, 3025), SimpleTechLevel.INTRO);
        MekSummary experimental = level(unit("Experimental", 1, 1, 1, 3025), SimpleTechLevel.EXPERIMENTAL);
        MekSummary advanced = level(unit("Advanced", 1, 1, 1, 3025), SimpleTechLevel.ADVANCED);
        MekSummary unofficial = level(unit("Unofficial", 1, 1, 1, 3025), SimpleTechLevel.UNOFFICIAL);
        MekSummary none = unit("None", 1, 1, 1, 3025);
        none.setTechLevel("");
        List<RequisitionRow> rows = rows(intro, experimental, advanced, unofficial, none);
        assertEquals(List.of("Intro", "Advanced", "Experimental", "Unofficial", "None"),
              names(rows, up(RequisitionSort.LEVEL)));
        assertEquals(List.of("Unofficial", "Experimental", "Advanced", "Intro", "None"),
              names(rows, down(RequisitionSort.LEVEL)));
    }

    /** {@code unit} at {@code level}, as the unit cache sets it: the name and its code. */
    private static MekSummary level(MekSummary unit, SimpleTechLevel level) {
        unit.setTechLevel(level.toString());
        unit.setTechLevelCode(level.ordinal());
        return unit;
    }

    @Test
    void typesSortByTheirNameAndTiesStayInNameOrder() {
        MekSummary tank = RequisitionCatalogTest.unit("Tank", "", UnitType.TANK, 3025, TechConstants.T_INTRO_BOX_SET);
        MekSummary mekB = unit("Bravo", 1, 1, 1, 3025);
        MekSummary mekA = unit("Alpha", 1, 1, 1, 3025);
        List<RequisitionRow> rows = rows(tank, mekB, mekA);
        boolean mekFirst = String.CASE_INSENSITIVE_ORDER.compare(RequisitionRow.typeName(mekA),
              RequisitionRow.typeName(tank)) < 0;
        List<String> expected = mekFirst ? List.of("Alpha", "Bravo", "Tank") : List.of("Tank", "Alpha", "Bravo");
        assertEquals(expected, names(rows, up(RequisitionSort.TYPE)));
        List<String> reversed = mekFirst ? List.of("Tank", "Alpha", "Bravo") : List.of("Alpha", "Bravo", "Tank");
        assertEquals(reversed, names(rows, down(RequisitionSort.TYPE)), "ties keep name order when reversed");
    }

    @Test
    void theSearchSortsItsResultsByTheQuerysOrder() {
        MekSummary atlas = unit("Atlas", 1897, 52, 100, 2755);
        MekSummary locust = unit("Locust", 432, 18, 20, 2499);
        MekSummary archer = unit("Archer", 1817, 38, 70, 2458);
        RequisitionSearch search = new RequisitionSearch(() -> RequisitionCatalog.index(
              new MekSummary[] { atlas, locust, archer }, unit -> false, unit -> null), Runnable::run);
        List<Result> results = new ArrayList<>();
        Query byName = new Query(Rules.OPEN, Category.ALL, Filters.NONE, "");
        search.search(byName, results::add);
        search.search(new Query(Rules.OPEN, Category.ALL, Filters.NONE, "", down(RequisitionSort.TONS)),
              results::add);
        search.search(new Query(Rules.OPEN, Category.ALL, Filters.NONE, "", null), results::add);

        assertEquals(RequisitionSort.BY_NAME, byName.sort());
        assertEquals(List.of("Archer", "Atlas", "Locust"), results.get(0).rows().stream().map(RequisitionRow::name)
              .toList());
        assertEquals(List.of("Atlas", "Archer", "Locust"), results.get(1).rows().stream().map(RequisitionRow::name)
              .toList());
        assertEquals(byName, results.get(2).query(), "no order is the name order");
        assertFalse(byName.equals(results.get(1).query()), "queries differing in order are different queries");
    }
}
