/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import megamek.client.lobby.RandomMapForm.Feature;
import megamek.client.lobby.RandomMapForm.Level;
import megamek.client.lobby.RandomMapForm.MountainStyle;
import megamek.client.lobby.RandomMapForm.Param;
import megamek.client.lobby.RandomMapForm.Row;
import megamek.client.lobby.RandomMapForm.Section;
import megamek.client.lobby.RandomMapForm.Tab;
import megamek.common.loaders.MapSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The random map settings form: the normal view's presets and readings, the advanced numbers, files. */
class RandomMapFormTest {
    private final List<MapSettings> sent = new ArrayList<>();
    private MapSettings source;
    private RandomMapForm form;

    @TempDir
    File folder;

    @BeforeEach
    void setUp() {
        source = MapSettings.getInstance();
        source.setBoardSize(16, 17);
        source.setMapSize(2, 1);
        source.setBoardsSelectedVector(List.of("a.board2", MapSettings.BOARD_GENERATED));
        source.setBoardTheme(0, "snow");
        source.setBoardLevelOffset(1, 2);
        form = new RandomMapForm(source, sent::add);
    }

    @Test
    void normalLevelsSetThePresetsAndResetTheEffects() {
        form.advanced(true);
        form.value(Param.ALGORITHM, 1);
        form.value(Param.FX_MOD, 3);
        form.invertNegative(true);
        form.advanced(false);

        form.level(Feature.WOODS, Level.HIGH);
        MapSettings s = form.settings();
        assertEquals(6, s.getMinForestSpots());
        assertEquals(13, s.getMaxForestSize());
        assertEquals(5, s.getProbUltra());
        assertEquals(Level.HIGH, form.level(Feature.WOODS));
        assertEquals(0, s.getAlgorithmToUse());
        assertEquals(0, s.getFxMod());
        assertFalse(form.invertNegative());
        assertEquals(6, form.value(Param.WOODS_MIN), "the advanced view reads the preset");
    }

    @Test
    void everyLevelReadsBackAsItself() {
        for (Feature feature : Feature.values()) {
            for (Level level : Level.values()) {
                form.level(feature, level);
                assertEquals(level, form.level(feature), feature + " " + level);
            }
        }
    }

    @Test
    void hillsAndMountainsUseTheirOwnPresets() {
        form.level(Feature.HILLS, Level.MEDIUM);
        assertEquals(50, form.settings().getHilliness());
        assertEquals(5, form.settings().getRange());
        form.level(Feature.MOUNTAINS, Level.HIGH);
        assertEquals(MapSettings.MOUNTAIN_SNOW_CAPPED, form.settings().getMountainStyle());
        assertEquals(MountainStyle.SNOW_CAPPED, form.mountainStyle());
    }

    @Test
    void cityTypeSetsThePresetOnlyInTheNormalView() {
        form.cityType("HUB");
        assertEquals("HUB", form.cityType());
        assertEquals(16, form.settings().getCityBlocks());
        form.cityType("NONE");
        assertEquals(0, form.settings().getCityBlocks());
        form.advanced(true);
        form.value(Param.CITY_BLOCKS, 7);
        form.cityType("TOWN");
        assertEquals(7, form.settings().getCityBlocks());
        assertEquals("TOWN", form.cityType());
    }

    @Test
    void advancedPairsStayOrderedAndInBounds() {
        form.value(Param.WOODS_MAX, 4);
        form.value(Param.WOODS_MIN, 9);
        assertEquals(9, form.value(Param.WOODS_MAX));
        form.value(Param.WOODS_MAX, 2);
        assertEquals(2, form.value(Param.WOODS_MIN));
        assertEquals(2, form.settings().getMaxForestSpots());
        form.value(Param.HILLINESS, 500);
        assertEquals(99, form.value(Param.HILLINESS));
        form.value(Param.CITY_CF_MIN, 0);
        assertEquals(1, form.value(Param.CITY_CF_MIN));
        form.value(Param.FX_MOD, -4);
        assertEquals(-4, form.settings().getFxMod());
        assertNull(form.problem());
    }

    @Test
    void rowsPairMinimaWithMaxima() {
        List<Row> rows = RandomMapForm.rows(Section.WOODS);
        assertEquals(4, rows.size());
        assertEquals(Param.WOODS_MIN, rows.get(0).first());
        assertEquals(Param.WOODS_MAX, rows.get(0).second());
        assertNull(rows.get(2).second());
        for (Tab tab : Tab.values()) {
            assertFalse(RandomMapForm.sections(tab).isEmpty(), tab.name());
        }
        assertFalse(RandomMapForm.features(Tab.EFFECTS).iterator().hasNext());
        assertFalse(Param.WOODS_MIN.label().endsWith(":"));
    }

    @Test
    void applySendsTheBoardSizeAndTheme() {
        form.boardSize(20, 22);
        form.theme("lunar");
        assertTrue(form.apply());
        assertEquals(1, sent.size());
        assertEquals(20, sent.get(0).getBoardWidth());
        assertEquals("lunar", sent.get(0).getTheme());
        form.boardSize(0, 22);
        assertNotNull(form.problem());
        assertFalse(form.apply());
        assertEquals(1, sent.size());
    }

    @Test
    void loadKeepsTheMapAndItsBoards() throws IOException {
        form.level(Feature.LAKES, Level.HIGH);
        form.theme("desert");
        File file = RandomMapForm.withExtension(new File(folder, "lakes"));
        assertTrue(form.applyAndSave(file));

        RandomMapForm other = new RandomMapForm(MapSettings.getInstance(), settings -> { });
        MapSettings otherSettings = other.settings();
        otherSettings.setMapSize(2, 1);
        otherSettings.setBoardsSelectedVector(List.of("b.board2", "c.board2"));
        otherSettings.setBoardTheme(1, "mars");
        other.load(file);
        assertEquals(Level.HIGH, other.level(Feature.LAKES));
        assertEquals("desert", other.theme());
        assertEquals(List.of("b.board2", "c.board2"), other.settings().getBoardsSelectedVector());
        assertEquals("mars", other.settings().getBoardTheme(1));
        assertThrows(FileNotFoundException.class, () -> other.load(new File(folder, "none.xml")));
    }
}
