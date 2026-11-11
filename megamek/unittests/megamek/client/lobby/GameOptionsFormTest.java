/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;

import megamek.client.ui.panels.GameOptionsCatalog;
import megamek.client.ui.settings.SettingsForm.Page;
import megamek.client.ui.settings.SettingsForm.Row;
import megamek.client.ui.settings.SettingsForm.Section;
import megamek.common.options.GameOptions;
import megamek.common.options.IBasicOption;
import megamek.common.options.IOption;
import megamek.common.options.OptionsConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GameOptionsFormTest {
    private static final String VICTORY = "victory";

    private GameOptions options;
    private GameOptionsForm form;

    @BeforeEach
    void setUp() {
        options = new GameOptions();
        form = new GameOptionsForm(options, Set.of(VICTORY), true);
    }

    private List<Row> allRows() {
        return form.pages().stream()
              .flatMap(page -> page.sections().stream())
              .flatMap(section -> section.rows().stream())
              .toList();
    }

    @Test
    void pagesStartWithTheLandingPageAndSkipTheToggleAndVictoryOptions() {
        Page landing = form.pages().get(0);
        assertTrue(landing.isLanding());
        assertTrue(landing.sections().stream().flatMap(s -> s.rows().stream())
              .anyMatch(row -> row.name().equals(OptionsConstants.RULES_SYSTEM)));
        List<String> names = allRows().stream().map(Row::name).toList();
        assertFalse(names.contains(OptionsConstants.BASE_HIDE_UNOFFICIAL));
        assertFalse(names.contains(OptionsConstants.BASE_HIDE_LEGACY));
        assertTrue(names.contains(OptionsConstants.RPG_INDIVIDUAL_INITIATIVE));
        assertFalse(names.contains(OptionsConstants.VICTORY_USE_BV_DESTROYED));
        assertTrue(form.pages().stream().skip(1).noneMatch(Page::isLanding));
        assertTrue(form.pages().stream().skip(1).allMatch(page -> !page.categoryTitle().isBlank()));
    }

    @Test
    void searchNarrowsThePagesSectionsAndRows() {
        int pagesBefore = form.visiblePages().size();
        form.search("dead zones");
        assertTrue(form.searching());
        List<Page> pages = form.visiblePages();
        assertTrue(pages.size() < pagesBefore);
        assertFalse(pages.isEmpty());
        boolean found = false;
        for (Page page : pages) {
            for (Section section : form.visibleSections(page)) {
                found |= form.visibleRows(page, section).stream()
                      .anyMatch(row -> row.name().equals(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_DEAD_ZONES));
            }
        }
        assertTrue(found);
        form.search("zzqx no such option");
        assertTrue(form.visiblePages().isEmpty());
        form.search("");
        assertEquals(pagesBefore, form.visiblePages().size());
    }

    @Test
    void hidingUnofficialRulesTurnsThemOffAndHidesThem() {
        assertTrue(form.hasUnofficialToggle());
        form.bool(OptionsConstants.ADVANCED_BA_GRAB_BARS, true);
        form.unofficialShown(true);
        Row grabBars = allRows().stream()
              .filter(row -> row.name().equals(OptionsConstants.ADVANCED_BA_GRAB_BARS)).findFirst().orElseThrow();
        assertTrue(form.shown(grabBars));
        form.unofficialShown(false);
        assertFalse(form.unofficialShown());
        assertTrue(form.bool(OptionsConstants.BASE_HIDE_UNOFFICIAL));
        assertFalse(form.bool(OptionsConstants.ADVANCED_BA_GRAB_BARS));
        assertFalse(form.shown(grabBars));
        form.unofficialShown(true);
        assertFalse(form.bool(OptionsConstants.BASE_HIDE_UNOFFICIAL));
        assertFalse(form.bool(OptionsConstants.ADVANCED_BA_GRAB_BARS));
    }

    @Test
    void individualInitiativeClearsAndLocksLanceMovement() {
        form.bool(OptionsConstants.ADVANCED_GROUND_MOVEMENT_MEK_LANCE_MOVEMENT, true);
        assertFalse(form.editable(OptionsConstants.RPG_INDIVIDUAL_INITIATIVE));
        form.bool(OptionsConstants.ADVANCED_GROUND_MOVEMENT_MEK_LANCE_MOVEMENT, false);
        assertTrue(form.editable(OptionsConstants.RPG_INDIVIDUAL_INITIATIVE));
        form.bool(OptionsConstants.INIT_INF_MOVE_EVEN, true);
        form.bool(OptionsConstants.RPG_INDIVIDUAL_INITIATIVE, true);
        assertFalse(form.bool(OptionsConstants.INIT_INF_MOVE_EVEN));
        assertFalse(form.editable(OptionsConstants.ADVANCED_GROUND_MOVEMENT_MEK_LANCE_MOVEMENT));
        assertFalse(form.editable(OptionsConstants.INIT_INF_MOVE_EVEN));
        assertFalse(form.editable(OptionsConstants.INIT_PROTOMEKS_MOVE_EVEN));
    }

    @Test
    void movementOrderOptionsExcludeEachOther() {
        assertTrue(form.editable(OptionsConstants.INIT_PROTOMEKS_MOVE_EVEN));
        assertFalse(form.editable(OptionsConstants.INIT_INF_DEPLOY_EVEN));
        form.bool(OptionsConstants.INIT_INF_MOVE_EVEN, true);
        assertTrue(form.editable(OptionsConstants.INIT_INF_DEPLOY_EVEN));
        assertFalse(form.editable(OptionsConstants.INIT_INF_MOVE_MULTI));
        form.bool(OptionsConstants.INIT_INF_DEPLOY_EVEN, true);
        form.bool(OptionsConstants.INIT_INF_MOVE_EVEN, false);
        assertFalse(form.bool(OptionsConstants.INIT_INF_DEPLOY_EVEN));
    }

    @Test
    void lineOfSightRulesExcludeEachOther() {
        form.bool(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_DEAD_ZONES, true);
        assertFalse(form.editable(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_LOS1));
        assertFalse(form.editable(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_LOS_RANGE));
        form.bool(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_RANGE, true);
        form.bool(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_LOS_RANGE, true);
        form.bool(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_RANGE, false);
        assertFalse(form.bool(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_LOS_RANGE));
    }

    @Test
    void ghostTargetMaximumOnlyForLegacyGhostTargets() {
        assertFalse(form.editable(OptionsConstants.ADVANCED_GHOST_TARGET_MODE));
        form.bool(OptionsConstants.ADVANCED_TAC_OPS_GHOST_TARGET, true);
        assertTrue(form.editable(OptionsConstants.ADVANCED_GHOST_TARGET_MODE));
        form.text(OptionsConstants.ADVANCED_GHOST_TARGET_MODE, OptionsConstants.GHOST_TARGET_MODE_LEGACY);
        assertTrue(form.editable(OptionsConstants.ADVANCED_GHOST_TARGET_MAX));
        form.text(OptionsConstants.ADVANCED_GHOST_TARGET_MODE, OptionsConstants.GHOST_TARGET_MODE_STANDARD);
        assertFalse(form.editable(OptionsConstants.ADVANCED_GHOST_TARGET_MAX));
    }

    @Test
    void coreRulesDisableTheOptionsTheyOverride() {
        form.text(OptionsConstants.RULES_SYSTEM, OptionsConstants.RULES_TW);
        // The set has no order, and some of its options also depend on others: take one free of those.
        String overridden = GameOptionsCatalog.CORE_RULES_DISABLED_OPTIONS.stream()
              .filter(name -> options.getOption(name) != null && form.editable(name))
              .sorted().findFirst().orElseThrow();
        form.text(OptionsConstants.RULES_SYSTEM, OptionsConstants.RULES_CORE);
        assertFalse(form.totalWarfare());
        assertFalse(form.editable(overridden));
        form.text(OptionsConstants.RULES_SYSTEM, OptionsConstants.RULES_TW);
        assertTrue(form.totalWarfare());
        assertTrue(form.editable(overridden));
        assertNotNull(form.rulesSystemDescription());
    }

    @Test
    void numbersAreValidatedAgainstTheirBounds() {
        assertNull(form.error(OptionsConstants.BASE_BRIDGE_CF));
        form.text(OptionsConstants.BASE_BRIDGE_CF, "5000");
        assertNotNull(form.error(OptionsConstants.BASE_BRIDGE_CF));
        assertFalse(form.valid());
        form.text(OptionsConstants.BASE_BRIDGE_CF, "abc");
        assertNotNull(form.error(OptionsConstants.BASE_BRIDGE_CF));
        form.text(OptionsConstants.BASE_BRIDGE_CF, "250");
        assertTrue(form.valid());
        Vector<IBasicOption> changed = form.changedOptions();
        assertEquals(1, changed.size());
        assertEquals(OptionsConstants.BASE_BRIDGE_CF, changed.get(0).getName());
        assertEquals(250, changed.get(0).getValue());
    }

    @Test
    void changedOptionsListOnlyDifferencesAndAllOptionsListsEverything() {
        assertTrue(form.changedOptions().isEmpty());
        assertFalse(form.modified());
        form.bool(OptionsConstants.BASE_FRIENDLY_FIRE, !options.booleanOption(OptionsConstants.BASE_FRIENDLY_FIRE));
        assertTrue(form.changed(OptionsConstants.BASE_FRIENDLY_FIRE));
        assertEquals(1, form.changedOptions().size());
        assertEquals(options.getOptionsList().size(), form.allOptions().size());
        form.bool(OptionsConstants.BASE_FRIENDLY_FIRE, options.booleanOption(OptionsConstants.BASE_FRIENDLY_FIRE));
        assertFalse(form.modified());
    }

    @Test
    void defaultsResetEveryOption() {
        form.bool(OptionsConstants.BASE_FRIENDLY_FIRE, true);
        form.text(OptionsConstants.BASE_BRIDGE_CF, "300");
        form.text(OptionsConstants.ALLOWED_TECH_LEVEL, "Experimental");
        form.defaults();
        for (String name : form.representedNames()) {
            IOption option = options.getOption(name);
            assertEquals(String.valueOf(option.getDefault()), String.valueOf(form.value(name)), name);
        }
    }

    @Test
    void loadTakesTheValuesWithoutTouchingTheGame() {
        GameOptions loaded = form.copyOfOptions();
        loaded.getOption(OptionsConstants.BASE_FRIENDLY_FIRE).setValue(true);
        loaded.getOption(OptionsConstants.BASE_BRIDGE_CF).setValue(400);
        form.load(loaded);
        assertTrue(form.bool(OptionsConstants.BASE_FRIENDLY_FIRE));
        assertEquals("400", form.text(OptionsConstants.BASE_BRIDGE_CF));
        assertFalse(options.booleanOption(OptionsConstants.BASE_FRIENDLY_FIRE));
        assertEquals(2, form.changedOptions().size());
    }

    @Test
    void grabBarsTellTheCaller() {
        AtomicInteger calls = new AtomicInteger();
        form.onGrabBarsChanged(calls::incrementAndGet);
        form.bool(OptionsConstants.ADVANCED_BA_GRAB_BARS, true);
        form.bool(OptionsConstants.ADVANCED_BA_GRAB_BARS, false);
        assertEquals(2, calls.get());
    }

    @Test
    void aViewOnlyFormEditsNothing() {
        GameOptionsForm viewOnly = new GameOptionsForm(options, Set.of(VICTORY), false);
        assertFalse(viewOnly.editable());
        assertFalse(viewOnly.editable(OptionsConstants.BASE_FRIENDLY_FIRE));
    }

    @Test
    void lockedOptionsKeepTheirValueWhateverChangesThem() {
        boolean fire = options.booleanOption(OptionsConstants.BASE_FRIENDLY_FIRE);
        form.bool(OptionsConstants.INIT_INF_MOVE_EVEN, true);
        form.lock(Set.of(OptionsConstants.BASE_FRIENDLY_FIRE, OptionsConstants.BASE_BRIDGE_CF,
              OptionsConstants.INIT_INF_MOVE_EVEN));
        assertTrue(form.locked(OptionsConstants.BASE_FRIENDLY_FIRE));
        assertFalse(form.editable(OptionsConstants.BASE_FRIENDLY_FIRE));
        assertFalse(form.editable(OptionsConstants.BASE_BRIDGE_CF));
        assertTrue(form.editable(OptionsConstants.BASE_DUMPING_FROM_ROUND));

        form.bool(OptionsConstants.BASE_FRIENDLY_FIRE, !fire);
        form.text(OptionsConstants.BASE_BRIDGE_CF, "300");
        // Individual initiative would clear the movement order option it locks out
        form.bool(OptionsConstants.RPG_INDIVIDUAL_INITIATIVE, true);
        assertEquals(fire, form.bool(OptionsConstants.BASE_FRIENDLY_FIRE));
        assertNotEquals("300", form.text(OptionsConstants.BASE_BRIDGE_CF));
        assertTrue(form.bool(OptionsConstants.INIT_INF_MOVE_EVEN));

        GameOptions loaded = form.copyOfOptions();
        loaded.getOption(OptionsConstants.BASE_FRIENDLY_FIRE).setValue(!fire);
        form.load(loaded);
        form.defaults();
        assertEquals(fire, form.bool(OptionsConstants.BASE_FRIENDLY_FIRE));
        assertTrue(form.bool(OptionsConstants.INIT_INF_MOVE_EVEN));
    }
}
