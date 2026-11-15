/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import megamek.client.ui.clientGUI.GUIPreferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The quality switches' saved form: presets, Custom sets, and the migration from the Quality level of before. */
class BoardSwitchesTest {
    private static final String[] KEYS = { GUIPreferences.BOARD_QUALITY, GUIPreferences.BOARD_QUALITY_SWITCHES,
          GUIPreferences.BOARD_SHADOWS, GUIPreferences.BOARD_SPECULAR_ANTI_ALIASING, GUIPreferences.BOARD_TERRAIN_PARALLAX,
          GUIPreferences.BOARD_TESSELLATION, GUIPreferences.BOARD_AMBIENT_OCCLUSION };
    private final GUIPreferences preferences = GUIPreferences.getInstance();
    private final Map<String, String> saved = new LinkedHashMap<>();

    @BeforeEach
    void keep() {
        for (String key : KEYS) {
            saved.put(key, preferences.getString(key));
            preferences.setValue(key, preferences.getDefaultString(key));
        }
    }

    @AfterEach
    void restore() {
        saved.forEach(preferences::setValue);
    }

    @Test
    void theOldOptionsDefaultsAreHighsSet() {
        preferences.setBoardQuality("HIGH");
        assertEquals(BoardSwitches.of(BoardSwitches.Preset.HIGH), BoardSwitches.saved(preferences));
        assertNull(BoardSwitches.savedCustom(preferences));
    }

    @Test
    void aHighOfBeforeKeepsThePlayersOptions() {
        preferences.setBoardQuality("HIGH");
        preferences.setValue(GUIPreferences.BOARD_AMBIENT_OCCLUSION, true);
        preferences.setValue(GUIPreferences.BOARD_SHADOWS, "HIGH");
        BoardSwitches expected = BoardSwitches.of(BoardSwitches.Preset.HIGH)
              .with(BoardSwitch.AMBIENT_OCCLUSION, true).with(BoardSwitch.HIGH_SHADOWS, true);
        assertEquals(expected, BoardSwitches.saved(preferences), "the picture stays as it was");
        assertEquals(BoardSwitches.Preset.CUSTOM, BoardSwitches.saved(preferences).preset());
        assertEquals(expected, BoardSwitches.savedCustom(preferences));
    }

    @Test
    void aMediumOfBeforeStaysMediumAndKeepsItsHighAsCustom() {
        preferences.setBoardQuality("MEDIUM");
        preferences.setValue(GUIPreferences.BOARD_AMBIENT_OCCLUSION, true);
        assertEquals(BoardSwitches.of(BoardSwitches.Preset.MEDIUM), BoardSwitches.saved(preferences),
              "the options did not count at Medium");
        assertEquals(BoardSwitches.of(BoardSwitches.Preset.HIGH).with(BoardSwitch.AMBIENT_OCCLUSION, true),
              BoardSwitches.savedCustom(preferences), "picking Custom gives what High gave");
    }

    @Test
    void aSaveEndsTheMigration() {
        preferences.setValue(GUIPreferences.BOARD_AMBIENT_OCCLUSION, true);
        BoardSwitches custom = BoardSwitches.of(BoardSwitches.Preset.LOW).with(BoardSwitch.DETAIL_SCALE, 1);
        BoardSwitches.save(preferences, BoardSwitches.of(BoardSwitches.Preset.HIGH), custom);
        assertEquals("HIGH", preferences.getBoardQuality());
        assertEquals(BoardSwitches.of(BoardSwitches.Preset.HIGH), BoardSwitches.saved(preferences),
              "a preset saved since is its own values, whatever the old options say");
        assertEquals(custom, BoardSwitches.savedCustom(preferences));
        BoardSwitches.save(preferences, custom, custom);
        assertEquals("CUSTOM", preferences.getBoardQuality());
        assertEquals(custom, BoardSwitches.saved(preferences));
    }

    @Test
    void theTextReadsBackAndToleratesWhatItDoesNotKnow() {
        BoardSwitches set = BoardSwitches.of(BoardSwitches.Preset.MEDIUM).with(BoardSwitch.MEADOW_SHARE, .35f)
              .with(BoardSwitch.TERRAIN_ANISOTROPY, 4);
        assertEquals(set, BoardSwitches.parse(set.format()));
        assertEquals(BoardSwitches.of(BoardSwitches.Preset.HIGH).with(BoardSwitch.AMBIENT_OCCLUSION, true),
              BoardSwitches.parse("high;AMBIENT_OCCLUSION=1;NO_SUCH_SWITCH=3;HALF_RATE_WAVES=x"));
        assertEquals(BoardSwitches.of(BoardSwitches.Preset.MEDIUM), BoardSwitches.parse(""), "nothing named is Medium");
        assertEquals(4, BoardSwitches.parse("DETAIL_SCALE=9").get(BoardSwitch.DETAIL_SCALE), "kept within the range");
    }

    @Test
    void thePresetIsDerivedFromTheValues() {
        for (BoardSwitches.Preset preset : new BoardSwitches.Preset[] { BoardSwitches.Preset.LOW, BoardSwitches.Preset.MEDIUM,
              BoardSwitches.Preset.HIGH }) {
            assertEquals(preset, BoardSwitches.of(preset).preset());
        }
        BoardSwitches medium = BoardSwitches.of(BoardSwitches.Preset.MEDIUM);
        assertEquals(BoardSwitches.Preset.CUSTOM, medium.with(BoardSwitch.MEADOW_SHARE, .65f).preset());
        assertEquals(BoardSwitches.Preset.MEDIUM, medium.with(BoardSwitch.MEADOW_SHARE, .65f)
              .with(BoardSwitch.MEADOW_SHARE, .6f).preset(), "back to Medium's value is Medium again");
        assertEquals(BoardSwitches.of(BoardSwitches.Preset.LOW).programKey(), medium.programKey(),
              "Low and Medium share their programs");
        assertTrue(medium.programKey() != BoardSwitches.of(BoardSwitches.Preset.HIGH).programKey());
        // Two sets that differ only in a keyed switch never share programs.
        for (BoardSwitch s : BoardSwitch.values()) {
            if (s.keyed()) {
                assertTrue(medium.with(s, true).programKey() != medium.with(s, false).programKey(), s + " changes the key");
            }
        }
    }
}
