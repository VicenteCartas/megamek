/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import megamek.client.lobby.RequisitionAdvancedSearch.Equipment;
import megamek.client.lobby.RequisitionAdvancedSearch.EquipmentChoice;
import megamek.client.lobby.RequisitionAdvancedSearch.EquipmentGroup;
import megamek.client.lobby.RequisitionAdvancedSearch.Field;
import megamek.client.lobby.RequisitionAdvancedSearch.Flag;
import megamek.client.lobby.RequisitionAdvancedSearch.Level;
import megamek.client.lobby.RequisitionAdvancedSearch.Minimums;
import megamek.client.lobby.RequisitionAdvancedSearch.Movement;
import megamek.client.lobby.RequisitionAdvancedSearch.Picks;
import megamek.client.lobby.RequisitionAdvancedSearch.Range;
import megamek.client.lobby.RequisitionAdvancedSearch.Term;
import megamek.client.lobby.SavedAdvancedSearches.Loaded;
import megamek.client.lobby.SavedAdvancedSearches.Saved;
import megamek.client.ui.dialogs.advancedsearch.AdvSearchState;
import megamek.client.ui.dialogs.advancedsearch.AdvancedSearchFiles;
import megamek.common.alphaStrike.ASUnitType;
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.battlefieldSupport.BFSAssetType;
import megamek.common.equipment.EquipmentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SavedAdvancedSearchesTest {

    @TempDir
    Path folder;

    @BeforeAll
    static void loadEquipment() {
        EquipmentType.initializeTypes();
    }

    private SavedAdvancedSearches store() {
        return new SavedAdvancedSearches(folder.resolve("searches"), folder.resolve("recent.json"));
    }

    private static Object choice(Field field, int index) {
        return RequisitionAdvancedSearch.choices(field).get(index).value();
    }

    private static Term item(EquipmentGroup group, int count, boolean atLeast) {
        EquipmentChoice choice = RequisitionAdvancedSearch.equipmentChoices().stream()
              .filter(each -> each.group() == group).findFirst().orElseThrow();
        return Term.item(choice.key(), choice.label(), count, atLeast);
    }

    private static Path dialogSearch(String name) throws Exception {
        return Path.of(SavedAdvancedSearchesTest.class.getClassLoader().getResource("searches/" + name).toURI());
    }

    @Test
    void aSearchOfEveryKindSurvivesSavingAndLoading() throws Exception {
        Object numericSpecial = choice(Field.BF_NUMERIC_SPECIALS, 0);
        RequisitionAdvancedSearch search = RequisitionAdvancedSearch.NONE
              .with(Field.WALK, new Range(3, 5))
              .with(Field.RUN, new Range(5, null))
              .with(Field.ARMOR, new Level(2))
              .with(Field.UNIT_TYPE, new Picks(List.of("Mek"), List.of("Tank"), false))
              .with(Field.TECH_BASE, new Picks(List.of(choice(Field.TECH_BASE, 1)), List.of(), false))
              .with(Field.ENGINE, new Picks(List.of(), List.of(choice(Field.ENGINE, 0)), false))
              .with(Field.QUIRKS, new Picks(List.of(choice(Field.QUIRKS, 0), choice(Field.QUIRKS, 1)), List.of(),
                    true))
              .with(Field.OMNI, new Flag(true))
              .with(Field.CLAN_ENGINE, new Flag(false))
              .with(Field.EQUIPMENT, new Equipment(List.of(item(EquipmentGroup.CLASS, 2, true), Term.AND,
                    item(EquipmentGroup.WEAPON, 1, false))))
              .with(Field.AS_TYPE, new Picks(List.of(ASUnitType.BM, ASUnitType.CV), List.of(), false))
              .with(Field.AS_SPECIALS, new Picks(List.of(BattleForceSUA.ECM, BattleForceSUA.TAG, BattleForceSUA.CASE),
                    List.of(), false))
              .with(Field.AS_DAMAGE_M, new Range(2, 5))
              .with(Field.AS_MOVE, new Movement("j", new Range(2, null)))
              .with(Field.AS_PV, new Range(null, 40))
              .with(Field.BF_TYPE, new Picks(List.of(BFSAssetType.values()[0]), List.of(), false))
              .with(Field.BF_NUMERIC_SPECIALS, new Minimums(Map.of(numericSpecial, 2)))
              .with(Field.BF_ARTILLERY, new Picks(List.of(), List.of(), false))
              .with(Field.BF_MP, new Range(1, 3));
        SavedAdvancedSearches store = store();

        Saved saved = store.save("Fast raiders", search);
        Loaded loaded = store.load(saved.file());

        assertEquals("Fast raiders", loaded.name());
        assertFalse(loaded.partial());
        assertEquals(search, loaded.search());
        // The unit selector's dialog reads the same file, and sees the first two specials
        AdvSearchState dialog = AdvancedSearchFiles.load(saved.file().toFile());
        assertEquals(BattleForceSUA.ECM, dialog.asState.ability1Value);
        assertEquals(BattleForceSUA.TAG, dialog.asState.ability2Value);
        assertEquals("3", dialog.twState.miscState.startWalk);
    }

    @Test
    void aDialogSearchLoadsAsTheLobbysCriteria() throws Exception {
        Loaded loaded = store().load(dialogSearch("C BA w Gauss Jump 2+.json"));

        assertEquals("C BA w/ Gauss Jump 2+", loaded.name());
        assertFalse(loaded.partial());
        RequisitionAdvancedSearch search = loaded.search();
        assertEquals(new Picks(List.of("BattleArmor"), List.of(), false), search.value(Field.UNIT_TYPE));
        assertEquals(new Range(2, null), search.value(Field.JUMP));
        assertEquals(new Picks(List.of("Clan"), List.of(), false), search.value(Field.TECH_BASE));
        Equipment equipment = (Equipment) search.value(Field.EQUIPMENT);
        assertEquals(1, equipment.terms().size());
        assertEquals("GAUSS__1__>=", equipment.terms().get(0).token());
        assertEquals(4, search.count());
    }

    @Test
    void criteriaTheLobbyLacksAreLeftOutAndNoted() throws Exception {
        Loaded loaded = store().load(dialogSearch("DS w Heavy Vee Bay.json"));

        assertTrue(loaded.partial());
        assertEquals(new Picks(List.of("Dropship"), List.of(), false), loaded.search().value(Field.UNIT_TYPE));
        assertEquals(1, loaded.search().count());
    }

    @Test
    void replacingTheLoadedSearchKeepsTheCriteriaTheLobbyLacks() throws Exception {
        SavedAdvancedSearches store = store();
        Path file = folder.resolve("searches").resolve("DS w Heavy Vee Bay.json");
        Files.createDirectories(file.getParent());
        Files.copy(dialogSearch("DS w Heavy Vee Bay.json"), file);
        Loaded loaded = store.load(file);
        RequisitionAdvancedSearch edited = loaded.search().with(Field.TONS, new Range(2000, null));

        store.save(loaded.name(), edited, file);
        Saved copy = store.save("Copy", edited, file);

        Loaded replaced = store.load(file);
        assertTrue(replaced.partial());
        assertEquals(edited, replaced.search());
        assertFalse(AdvancedSearchFiles.isDefault(AdvancedSearchFiles.load(file.toFile()).twState.transportsState));
        assertFalse(store.load(copy.file()).partial(), "a search saved under another name has only what shows");
    }

    @Test
    void theListShowsTheRecentSearchesFirstAndRenamingKeepsTheCriteria() throws Exception {
        SavedAdvancedSearches store = store();
        RequisitionAdvancedSearch walking = RequisitionAdvancedSearch.NONE.with(Field.WALK, new Range(4, null));
        RequisitionAdvancedSearch jumping = RequisitionAdvancedSearch.NONE.with(Field.JUMP, new Range(3, null));
        Saved alpha = store.save("Alpha", walking);
        store.save("Bravo", jumping);
        Files.writeString(folder.resolve("searches").resolve("notes.json"), "{\"not\": \"a search\"}");

        store.load(alpha.file());
        assertEquals(List.of("Alpha", "Bravo"), store.list().stream().map(Saved::name).toList());
        assertTrue(store.exists("Alpha"));
        assertTrue(store.editable(alpha));

        Saved renamed = store.rename(alpha, "Charlie");
        assertFalse(Files.exists(alpha.file()));
        assertEquals(walking, store.load(renamed.file()).search());
        assertEquals("Charlie", store.load(renamed.file()).name());
        assertThrows(FileAlreadyExistsException.class, () -> store.rename(renamed, "Bravo"));
        // Only the case changes: the search keeps its file and takes the new name
        Saved recased = store.rename(renamed, "CHARLIE");
        assertEquals("CHARLIE", store.load(recased.file()).name());

        store.delete(recased);
        assertEquals(List.of("Bravo"), store.list().stream().map(Saved::name).toList());
    }

    @Test
    void aNameWithoutFileCharactersMakesNoFile() {
        assertNull(store().fileFor("???"));
        assertNull(store().fileFor("  "));
        assertEquals(folder.resolve("searches").resolve("AB.json"), store().fileFor(" A/B "));
    }
}
