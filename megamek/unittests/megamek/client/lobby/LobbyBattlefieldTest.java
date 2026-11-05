/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import megamek.common.loaders.MapSettings;
import megamek.common.options.GameOptions;
import megamek.common.options.OptionsConstants;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.common.planetaryConditions.Wind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LobbyBattlefieldTest {

    private static final String[] VICTORY_CONDITIONS = {
          OptionsConstants.VICTORY_USE_BV_DESTROYED, OptionsConstants.VICTORY_USE_BV_RATIO,
          OptionsConstants.VICTORY_USE_KILL_COUNT, OptionsConstants.VICTORY_COMMANDER_KILLED,
          OptionsConstants.VICTORY_USE_OBJECTIVES, OptionsConstants.VICTORY_USE_SENSOR_CHECK,
          OptionsConstants.VICTORY_USE_GAME_TURN_LIMIT };

    private GameOptions options;

    @BeforeEach
    void setUp() {
        options = new GameOptions();
        for (String condition : VICTORY_CONDITIONS) {
            set(condition, false);
        }
    }

    private void set(String name, Object value) {
        options.getOption(name).setValue(value);
    }

    private static MapSettings map(String... boards) {
        MapSettings settings = MapSettings.getInstance();
        settings.setBoardSize(16, 17);
        settings.setMapSize(boards.length, 1);
        settings.setBoardsSelectedVector(List.of(boards));
        return settings;
    }

    @Test
    void aSingleFixedSheetShowsOnlyItsSize() {
        assertEquals("16 \u00d7 17 hexes", LobbyBattlefield.mapLine(map("16x17/Grassland 1.board")));
    }

    @Test
    void severalSheetsAndARolledOneAreNamed() {
        String line = LobbyBattlefield.mapLine(map("16x17/Grassland 1.board", MapSettings.BOARD_GENERATED));

        assertEquals("32 \u00d7 17 hexes \u00b7 2 sheets \u00b7 Rolled at launch", line);
    }

    @Test
    void generatedRandomAndSurpriseSheetsAreRolled() {
        assertFalse(LobbyBattlefield.rolled(map("16x17/Grassland 1.board")));
        assertTrue(LobbyBattlefield.rolled(map(MapSettings.BOARD_GENERATED)));
        assertTrue(LobbyBattlefield.rolled(map(MapSettings.BOARD_RANDOM)));
        assertTrue(LobbyBattlefield.rolled(map(MapSettings.BOARD_SURPRISE + "a\nb")));
        assertTrue(LobbyBattlefield.of(null, null, map(MapSettings.BOARD_GENERATED),
              LobbyBattlefield.AutoResolve.DEFAULT).rolled(), "the column learns the map is rolled");
        assertFalse(LobbyBattlefield.of(null, null, map("16x17/Grassland 1.board"),
              LobbyBattlefield.AutoResolve.DEFAULT).rolled());
    }

    @Test
    void surpriseSheetsCannotBeGeneratedAhead() {
        assertTrue(LobbyBattlefield.generable(map("16x17/Grassland 1.board", MapSettings.BOARD_GENERATED)));
        assertFalse(LobbyBattlefield.generable(map(MapSettings.BOARD_SURPRISE + "a\nb")));
    }

    @Test
    void spaceMapsSayTheyAreInSpace() {
        MapSettings settings = map("16x17/Grassland 1.board");
        settings.setMedium(MapSettings.MEDIUM_SPACE);

        assertTrue(LobbyBattlefield.mapLine(settings).startsWith("Space \u00b7 "));
        assertTrue(LobbyBattlefield.of(options, null, settings, LobbyBattlefield.AutoResolve.DEFAULT).space());
    }

    @Test
    void ordinaryConditionsShowOnlyTheLight() {
        PlanetaryConditions conditions = new PlanetaryConditions();

        assertEquals(conditions.getLight().toString(), LobbyBattlefield.conditions(conditions));
    }

    @Test
    void unusualConditionsAreListed() {
        PlanetaryConditions conditions = new PlanetaryConditions();
        conditions.setWind(Wind.STORM);
        conditions.setGravity(0.5f);
        conditions.setTemperature(-60);

        String line = LobbyBattlefield.conditions(conditions);

        assertEquals(String.join(" \u00b7 ", conditions.getLight().toString(), Wind.STORM.toString(), "0.5 g",
              "-60 \u00b0C"), line);
    }

    @Test
    void theRulesLineShowsTheYearAndTechLevel() {
        set(OptionsConstants.ALLOWED_YEAR, 3067);

        String line = LobbyBattlefield.rulesLine(options);

        assertTrue(line.startsWith("3067 \u00b7 "), line);
        assertTrue(line.endsWith(" tech"), line);
    }

    @Test
    void realBlindDropHidesBlindDrop() {
        set(OptionsConstants.BASE_BLIND_DROP, true);
        assertTrue(LobbyBattlefield.notableRules(options).contains("Blind drop"));

        set(OptionsConstants.BASE_REAL_BLIND_DROP, true);
        List<String> rules = LobbyBattlefield.notableRules(options);

        assertTrue(rules.contains("Real blind drop"));
        assertFalse(rules.contains("Blind drop"));
    }

    @Test
    void withoutConditionsTheLastSideStandingWins() {
        assertEquals("Last side standing", LobbyBattlefield.victory(options));
    }

    @Test
    void conditionsAreListedWithTheTurnLimit() {
        set(OptionsConstants.VICTORY_USE_BV_DESTROYED, true);
        set(OptionsConstants.VICTORY_BV_DESTROYED_PERCENT, 75);
        set(OptionsConstants.VICTORY_COMMANDER_KILLED, true);
        set(OptionsConstants.VICTORY_ACHIEVE_CONDITIONS, 1);
        set(OptionsConstants.VICTORY_USE_GAME_TURN_LIMIT, true);
        set(OptionsConstants.VICTORY_GAME_TURN_LIMIT, 12);

        assertEquals("75% BV destroyed \u00b7 Commander killed \u00b7 12 turns", LobbyBattlefield.victory(options));
    }

    @Test
    void someButNotAllConditionsAreNeeded() {
        set(OptionsConstants.VICTORY_USE_KILL_COUNT, true);
        set(OptionsConstants.VICTORY_GAME_KILL_COUNT, 4);
        set(OptionsConstants.VICTORY_COMMANDER_KILLED, true);
        set(OptionsConstants.VICTORY_USE_OBJECTIVES, true);
        set(OptionsConstants.VICTORY_ACHIEVE_CONDITIONS, 2);

        assertEquals("Any 2 of: 4 kills \u00b7 Commander killed \u00b7 Objectives", LobbyBattlefield.victory(options));
    }

    @Test
    void realBlindDropHidesAutoResolve() {
        assertTrue(LobbyBattlefield.autoResolveShown(true, options));
        assertFalse(LobbyBattlefield.autoResolveShown(false, options));

        set(OptionsConstants.BASE_REAL_BLIND_DROP, true);

        assertFalse(LobbyBattlefield.autoResolveShown(true, options));
    }

    @Test
    void autoResolveSettingsStayInBounds() {
        LobbyBattlefield.AutoResolve settings = new LobbyBattlefield.AutoResolve(true, true, 10, 2, 8);

        assertEquals(1, LobbyBattlefield.AutoResolve.clampRuns(0));
        assertEquals(LobbyBattlefield.AutoResolve.MAX_RUNS, LobbyBattlefield.AutoResolve.clampRuns(5000));
        assertEquals(8, settings.clampThreads(20));
        assertEquals(1, settings.clampThreads(-3));
    }

    @Test
    void anEmptyGameSumsUpToNothing() {
        LobbyBattlefield battlefield = LobbyBattlefield.of(null, null, null, LobbyBattlefield.AutoResolve.DEFAULT);

        assertEquals("", battlefield.mapLine());
        assertEquals(List.of(), battlefield.notableRules());
        assertFalse(battlefield.autoResolve().shown());
    }
}
