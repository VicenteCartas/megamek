/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import megamek.client.lobby.RequisitionCatalog.Category;
import megamek.client.lobby.RequisitionCatalog.Either;
import megamek.client.lobby.RequisitionCatalog.ExactType;
import megamek.client.lobby.RequisitionCatalog.Filters;
import megamek.client.lobby.RequisitionCatalog.Rules;
import megamek.client.lobby.RequisitionCatalog.TechBase;
import megamek.client.lobby.RequisitionCatalog.Weight;
import megamek.client.lobby.RequisitionCatalog.YearSpan;
import megamek.common.TechConstants;
import megamek.common.loaders.MekSummary;
import megamek.common.options.GameOptions;
import megamek.common.options.OptionsConstants;
import megamek.common.units.EntityWeightClass;
import megamek.common.units.UnitType;
import org.junit.jupiter.api.Test;

class RequisitionCatalogTest {

    private static final Rules STANDARD_3067 = new Rules(3067, false, TechConstants.T_SIMPLE_STANDARD, false, false);

    static MekSummary unit(String chassis, String model, int unitType, int year, int techCode) {
        MekSummary unit = new MekSummary();
        unit.setChassis(chassis);
        unit.setModel(model);
        unit.setName(model.isEmpty() ? chassis : chassis + " " + model);
        unit.setUnitType(UnitType.getTypeName(unitType));
        unit.setYear(year);
        unit.setType(techCode);
        unit.setTechBase("Inner Sphere");
        unit.setWeightClass(EntityWeightClass.WEIGHT_HEAVY);
        unit.setLevel("A");
        return unit;
    }

    static MekSummary mek(String chassis, String model) {
        return unit(chassis, model, UnitType.MEK, 3025, TechConstants.T_INTRO_BOX_SET);
    }

    private static List<String> names(List<MekSummary> units) {
        return units.stream().map(MekSummary::getName).toList();
    }

    private static RequisitionCatalog.Index index(MekSummary... units) {
        return RequisitionCatalog.index(units, unit -> false, unit -> null);
    }

    // ---- Rules ----

    @Test
    void rulesReadTheGameOptionsTheDialogReads() {
        GameOptions options = new GameOptions();
        options.getOption(OptionsConstants.ALLOWED_YEAR).setValue(3050);
        options.getOption(OptionsConstants.ALLOWED_CANON_ONLY).setValue(true);
        options.getOption(OptionsConstants.ALLOWED_ERA_BASED).setValue(true);
        options.getOption(OptionsConstants.ALLOWED_ALLOW_ILLEGAL_UNITS).setValue(false);
        options.getOption(OptionsConstants.ALLOWED_TECH_LEVEL)
              .setValue(TechConstants.T_SIMPLE_NAMES[TechConstants.T_SIMPLE_ADVANCED]);

        Rules rules = Rules.of(options);

        assertEquals(new Rules(3050, true, TechConstants.T_SIMPLE_ADVANCED, true, false), rules);
    }

    @Test
    void unitsNewerThanTheGameYearAreLeftOut() {
        assertTrue(STANDARD_3067.allows(unit("A", "", UnitType.MEK, 3067, TechConstants.T_IS_TW_NON_BOX)));
        assertFalse(STANDARD_3067.allows(unit("A", "", UnitType.MEK, 3068, TechConstants.T_IS_TW_NON_BOX)));
    }

    @Test
    void unitsAboveTheGameTechLevelAreLeftOut() {
        assertTrue(STANDARD_3067.allows(unit("A", "", UnitType.MEK, 3000, TechConstants.T_CLAN_TW)));
        assertFalse(STANDARD_3067.allows(unit("A", "", UnitType.MEK, 3000, TechConstants.T_IS_ADVANCED)));
        assertFalse(STANDARD_3067.allows(unit("A", "", UnitType.MEK, 3000, TechConstants.T_TECH_UNKNOWN)));
    }

    @Test
    void theAllLevelsNeverMatchAsTheDialogListsThemNot() {
        Rules open = new Rules(4000, false, TechConstants.T_SIMPLE_UNOFFICIAL, false, true);
        assertFalse(open.allows(unit("A", "", UnitType.MEK, 3000, TechConstants.T_IS_TW_ALL)));
        assertFalse(open.allows(unit("A", "", UnitType.MEK, 3000, TechConstants.T_TW_ALL)));
        assertTrue(open.allows(unit("A", "", UnitType.MEK, 3000, TechConstants.T_CLAN_UNOFFICIAL)));
    }

    @Test
    void eraBasedLevelsJudgeTheUnitInTheGameYear() {
        MekSummary unit = unit("A", "", UnitType.MEK, 2900, TechConstants.T_IS_ADVANCED);
        unit.setAltTypes(new int[] { TechConstants.T_IS_TW_NON_BOX, TechConstants.T_IS_ADVANCED,
                                     TechConstants.T_IS_EXPERIMENTAL });
        unit.setStandardYear(3000);
        unit.setAdvancedYear(2950);

        assertTrue(new Rules(3067, true, TechConstants.T_SIMPLE_STANDARD, false, false).allows(unit));
        assertFalse(new Rules(2960, true, TechConstants.T_SIMPLE_STANDARD, false, false).allows(unit));
        assertFalse(STANDARD_3067.allows(unit));
    }

    @Test
    void canonOnlyGamesLeaveOutNonCanonUnits() {
        MekSummary unit = mek("Homebrew", "X");
        unit.setNonCanonBySource(true);
        assertTrue(STANDARD_3067.allows(unit));
        assertFalse(new Rules(3067, false, TechConstants.T_SIMPLE_STANDARD, true, false).allows(unit));
    }

    @Test
    void invalidUnitsNeedTheGameToAllowThem() {
        MekSummary unit = mek("Broken", "X");
        unit.setLevel("F");
        assertFalse(STANDARD_3067.allows(unit));
        assertTrue(new Rules(3067, false, TechConstants.T_SIMPLE_STANDARD, false, true).allows(unit));
    }

    @Test
    void theDrawerOffersNoRulesLevelAboveTheGames() {
        assertEquals(List.of(TechConstants.T_SIMPLE_INTRO, TechConstants.T_SIMPLE_STANDARD), STANDARD_3067.levels());
        assertEquals(5, Rules.OPEN.levels().size());
    }

    // ---- Categories and types ----

    @Test
    void categoriesTakeTheirUnitTypes() {
        assertTrue(Category.MEKS.contains(UnitType.getTypeName(UnitType.MEK)));
        assertFalse(Category.MEKS.contains(UnitType.getTypeName(UnitType.TANK)));
        assertTrue(Category.VEHICLES.contains(UnitType.getTypeName(UnitType.VTOL)));
        assertTrue(Category.LARGE_CRAFT.contains(UnitType.getTypeName(UnitType.WARSHIP)));
        assertTrue(Category.ALL.contains("Anything"));
    }

    @Test
    void otherTakesTheTypesNoDepartmentHas() {
        for (int type : List.of(UnitType.HANDHELD_WEAPON, UnitType.MOBILE_STRUCTURE, UnitType.ADVANCED_BUILDING,
              UnitType.BATTLEFIELD_SUPPORT_ASSET)) {
            assertTrue(Category.OTHER.contains(UnitType.getTypeName(type)), UnitType.getTypeName(type));
        }
        assertFalse(Category.OTHER.contains(UnitType.getTypeName(UnitType.MEK)));
        assertEquals(4, ExactType.of(Category.OTHER).size());
        assertFalse(Filters.weighs(Category.OTHER));
    }

    @Test
    void everyUnitTypeHasADepartment() {
        for (int type = 0; type < UnitType.SIZE; type++) {
            String name = UnitType.getTypeName(type);
            boolean placed = false;
            for (Category category : Category.values()) {
                placed |= (category != Category.ALL) && category.contains(name);
            }
            assertTrue(placed, name);
        }
    }

    @Test
    void everyTypeAndCategoryHasItsText() {
        for (Category category : Category.values()) {
            assertFalse(megamek.client.ui.Messages.getString(category.messageKey()).startsWith("!"),
                  category.messageKey());
        }
        for (ExactType type : ExactType.values()) {
            assertFalse(megamek.client.ui.Messages.getString(type.messageKey()).startsWith("!"), type.messageKey());
        }
    }

    @Test
    void singleTypeCategoriesOfferNoTypeRow() {
        assertTrue(ExactType.of(Category.MEKS).isEmpty());
        assertEquals(5, ExactType.of(Category.VEHICLES).size());
        assertTrue(ExactType.of(Category.LARGE_CRAFT).stream().allMatch(type -> type.category()
              == Category.LARGE_CRAFT));
    }

    @Test
    void aSupportVehicleIsOneWhateverItsMotiveType() {
        MekSummary truck = unit("Truck", "", UnitType.TANK, 3000, TechConstants.T_INTRO_BOX_SET);
        truck.setSupport(true);
        assertTrue(ExactType.SUPPORT_VEHICLE.matches(truck));
        assertTrue(ExactType.TANK.matches(truck));
        assertFalse(ExactType.VTOL.matches(truck));
    }

    @Test
    void techBasesReadTheUnitFileText() {
        assertSame(TechBase.IS, TechBase.of("Inner Sphere"));
        assertSame(TechBase.CLAN, TechBase.of("Clan"));
        assertSame(TechBase.MIXED, TechBase.of("Mixed (Clan Chassis)"));
        assertSame(TechBase.IS, TechBase.of(null));
    }

    // ---- Filters ----

    @Test
    void weightChipsTakeTheNeighbouringClasses() {
        MekSummary ultraLight = mek("Tiny", "");
        ultraLight.setWeightClass(EntityWeightClass.WEIGHT_ULTRA_LIGHT);
        Filters light = Filters.NONE.withWeights(Set.of(Weight.LIGHT));
        assertTrue(light.allows(ultraLight, STANDARD_3067, Category.ALL));
        assertFalse(light.allows(mek("Heavy", ""), STANDARD_3067, Category.ALL));
    }

    @Test
    void weightChipsMeanNothingToLargeCraft() {
        assertFalse(Filters.weighs(Category.LARGE_CRAFT));
        MekSummary dropShip = unit("Union", "", UnitType.DROPSHIP, 2700, TechConstants.T_INTRO_BOX_SET);
        dropShip.setWeightClass(EntityWeightClass.WEIGHT_SMALL_DROP);
        assertTrue(Filters.NONE.withWeights(Set.of(Weight.LIGHT)).allows(dropShip, STANDARD_3067,
              Category.LARGE_CRAFT));
    }

    @Test
    void drawerFiltersNarrowTheResults() {
        MekSummary clan = mek("Mad Cat", "Prime");
        clan.setTechBase("Clan");
        clan.setType(TechConstants.T_CLAN_TW);
        clan.setSource("TRO:3050");
        clan.setPublished("RS:3050");
        MekSummary is = mek("Atlas", "AS7-D");

        Filters clanOnly = Filters.NONE.withBases(Set.of(TechBase.CLAN));
        assertTrue(clanOnly.allows(clan, STANDARD_3067, Category.ALL));
        assertFalse(clanOnly.allows(is, STANDARD_3067, Category.ALL));

        Filters standard = Filters.NONE.withLevels(Set.of(TechConstants.T_SIMPLE_STANDARD));
        assertTrue(standard.allows(clan, STANDARD_3067, Category.ALL));
        assertFalse(standard.allows(is, STANDARD_3067, Category.ALL));

        Filters sheet = Filters.NONE.withRecordSheet(Either.YES);
        assertTrue(sheet.allows(clan, STANDARD_3067, Category.ALL));
        assertFalse(sheet.allows(is, STANDARD_3067, Category.ALL));

        Filters source = Filters.NONE.withSource("TRO:3050");
        assertTrue(source.allows(clan, STANDARD_3067, Category.ALL));
        assertFalse(source.allows(is, STANDARD_3067, Category.ALL));

        Filters era = Filters.NONE.withEra(new YearSpan("Succession Wars", 2781, 3049));
        assertTrue(era.allows(is, STANDARD_3067, Category.ALL));
        clan.setYear(3050);
        assertFalse(era.allows(clan, STANDARD_3067, Category.ALL));
    }

    @Test
    void theCanonFilterStandsAsideInCanonOnlyGames() {
        MekSummary canon = mek("Atlas", "AS7-D");
        Filters notCanon = Filters.NONE.withCanon(Either.NO);
        assertFalse(notCanon.allows(canon, STANDARD_3067, Category.ALL));
        assertTrue(notCanon.allows(canon, new Rules(3067, false, TechConstants.T_SIMPLE_STANDARD, true, false),
              Category.ALL));
    }

    @Test
    void aTypeOfAnotherCategoryIsIgnored() {
        Filters vtols = Filters.NONE.withType(ExactType.VTOL);
        assertTrue(vtols.allows(mek("Atlas", ""), STANDARD_3067, Category.MEKS));
        assertFalse(vtols.allows(unit("Tank", "", UnitType.TANK, 3000, 0), STANDARD_3067, Category.VEHICLES));
    }

    @Test
    void drawerCountLeavesTheWeightChipsOut() {
        Filters filters = Filters.NONE.withWeights(Set.of(Weight.HEAVY, Weight.ASSAULT))
              .withBases(Set.of(TechBase.IS, TechBase.CLAN)).withCanon(Either.YES).withSource("X");
        assertEquals(4, filters.drawerCount());
        assertEquals(Filters.NONE.withWeights(Set.of(Weight.HEAVY, Weight.ASSAULT)), filters.withoutDrawer());
        assertEquals(0, filters.withoutDrawer().drawerCount());
    }

    @Test
    void theWeightsBasesAndLevelsAreKeptAndReadBack() {
        Filters filters = Filters.NONE.withWeights(Set.of(Weight.HEAVY, Weight.LIGHT))
              .withBases(Set.of(TechBase.CLAN))
              .withLevels(Set.of(TechConstants.T_SIMPLE_STANDARD, TechConstants.T_SIMPLE_INTRO))
              .withCanon(Either.YES)
              .withRecordSheet(Either.NO)
              .withType(ExactType.VTOL);
        String kept = filters.kept();
        assertEquals("W:LIGHT,HEAVY;B:CLAN;L:" + TechConstants.T_SIMPLE_INTRO + "," + TechConstants.T_SIMPLE_STANDARD,
              kept);

        Filters read = Filters.NONE.withKept(kept,
              List.of(TechConstants.T_SIMPLE_INTRO, TechConstants.T_SIMPLE_STANDARD));
        assertEquals(Set.of(Weight.LIGHT, Weight.HEAVY), read.weights());
        assertEquals(Set.of(TechBase.CLAN), read.bases());
        assertEquals(Set.of(TechConstants.T_SIMPLE_INTRO, TechConstants.T_SIMPLE_STANDARD), read.levels());
        // Canon, record sheet, type and the advanced search start afresh each game
        assertEquals(Either.ANY, read.canon());
        assertEquals(Either.ANY, read.recordSheet());
        assertNull(read.type());
        assertEquals(kept, read.kept());
    }

    @Test
    void readingKeptFiltersKeepsTheRestAndDropsWhatNoLongerFits() {
        Filters current = Filters.NONE.withCanon(Either.YES).withWeights(Set.of(Weight.MEDIUM));
        Filters read = current.withKept("W:ASSAULT,ULTRA;B:IS,NOPE;L:" + TechConstants.T_SIMPLE_INTRO + ","
              + TechConstants.T_SIMPLE_UNOFFICIAL + ",x;Z:1;junk", List.of(TechConstants.T_SIMPLE_INTRO));
        assertEquals(Set.of(Weight.ASSAULT), read.weights());
        assertEquals(Set.of(TechBase.IS), read.bases());
        assertEquals(Set.of(TechConstants.T_SIMPLE_INTRO), read.levels());
        assertEquals(Either.YES, read.canon());

        Filters nothing = current.withKept(null, List.of());
        assertTrue(nothing.weights().isEmpty());
        assertEquals(Either.YES, nothing.canon());
        assertEquals("W:;B:;L:", Filters.NONE.kept());
        assertEquals(Filters.NONE, Filters.NONE.withKept(Filters.NONE.kept(), List.of(1, 2, 3)));
    }

    @Test
    void theCategoryTabIsKeptWithTheFilters() {
        Filters filters = Filters.NONE.withWeights(Set.of(Weight.HEAVY));
        String kept = filters.kept(Category.MEKS);
        assertEquals("C:MEKS;W:HEAVY;B:;L:", kept);
        assertEquals(Category.MEKS, Filters.keptCategory(kept));
        assertEquals(Set.of(Weight.HEAVY), Filters.NONE.withKept(kept, List.of()).weights());
        // Lines from before the tab was kept, or naming a tab this version lacks, open on All
        assertEquals(Category.ALL, Filters.keptCategory("W:HEAVY;B:;L:"));
        assertEquals(Category.ALL, Filters.keptCategory("C:NOPE;W:"));
        assertEquals(Category.ALL, Filters.keptCategory(null));
    }

    @Test
    void clearingTheDrawerClearsTheKeptBasesAndLevels() {
        Filters filters = Filters.NONE.withWeights(Set.of(Weight.LIGHT)).withBases(Set.of(TechBase.MIXED))
              .withLevels(Set.of(TechConstants.T_SIMPLE_ADVANCED));
        assertEquals("W:LIGHT;B:;L:", filters.withoutDrawer().kept());
    }

    // ---- The index and the search ----

    @Test
    void theIndexSortsByNameWithoutLeadingMarks() {
        RequisitionCatalog.Index index = index(mek("Zeus", "ZEU-6S"), mek("'Astrolux'", ""), mek("Atlas", "AS7-D"));
        assertEquals(List.of("'Astrolux'", "Atlas AS7-D", "Zeus ZEU-6S"), names(RequisitionCatalog.offer(index,
              STANDARD_3067, Category.ALL, Filters.NONE, "")));
        assertEquals("astrolux'", RequisitionCatalog.sortKey("'Astrolux'"));
    }

    @Test
    void theIndexSkipsWhatIsListedElsewhere() {
        MekSummary asset = mek("Asset", "");
        RequisitionCatalog.Index index = RequisitionCatalog.index(new MekSummary[] { mek("Atlas", ""), asset, null },
              unit -> unit == asset, unit -> null);
        assertEquals(1, index.size());
        assertEquals(0, RequisitionCatalog.index(null, unit -> false, unit -> null).size());
    }

    @Test
    void theSearchMatchesEveryWordWithoutCaseOrAccents() {
        RequisitionCatalog.Index index = index(mek("Atlas", "AS7-D"), mek("Atlas", "AS7-K"), mek("Archer", "ARC-2R"),
              mek("Pégase", ""));
        assertEquals(List.of("Atlas AS7-D", "Atlas AS7-K"), names(RequisitionCatalog.offer(index, STANDARD_3067,
              Category.ALL, Filters.NONE, "atlas")));
        assertEquals(List.of("Atlas AS7-K"), names(RequisitionCatalog.offer(index, STANDARD_3067, Category.ALL,
              Filters.NONE, "  AS7-k  atLAS ")));
        assertEquals(List.of("Pégase"), names(RequisitionCatalog.offer(index, STANDARD_3067, Category.ALL,
              Filters.NONE, "pegase")));
        assertTrue(RequisitionCatalog.offer(index, STANDARD_3067, Category.ALL, Filters.NONE, "atlas archer")
              .isEmpty());
    }

    @Test
    void theSearchReadsTheAssetCard() {
        MekSummary base = mek("Locust", "LCT-1V");
        MekSummary asset = mek("Locust", "BSP");
        asset.setBfsCardTitle("Light Recon Lance");
        String text = RequisitionCatalog.searchText(base, asset);
        assertTrue(RequisitionCatalog.matches(text, RequisitionCatalog.words("recon locust")));
        assertEquals(0, RequisitionCatalog.words("   ").length);
    }

    @Test
    void offerAppliesTheRulesTheCategoryAndTheFilters() {
        MekSummary tank = unit("Demolisher", "", UnitType.TANK, 3000, TechConstants.T_INTRO_BOX_SET);
        MekSummary future = unit("Atlas", "III", UnitType.MEK, 3090, TechConstants.T_IS_TW_NON_BOX);
        RequisitionCatalog.Index index = index(mek("Atlas", "AS7-D"), tank, future);
        assertEquals(List.of("Atlas AS7-D"), names(RequisitionCatalog.offer(index, STANDARD_3067, Category.MEKS,
              Filters.NONE, null)));
        assertEquals(List.of("Demolisher"), names(RequisitionCatalog.offer(index, STANDARD_3067, Category.VEHICLES,
              Filters.NONE, null)));
    }

    @Test
    void sourcesAreThoseOfAllowedUnitsSorted() {
        MekSummary a = mek("A", "");
        a.setSource(" TRO:3039 ");
        MekSummary b = mek("B", "");
        b.setSource("tro:3025");
        MekSummary late = unit("C", "", UnitType.MEK, 3100, 0);
        late.setSource("TRO:3145");
        assertEquals(List.of("tro:3025", "TRO:3039"), RequisitionCatalog.sources(index(a, b, late, mek("D", "")),
              STANDARD_3067));
    }
}
