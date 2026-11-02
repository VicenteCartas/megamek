/*
 * Copyright (c) 2000-2005 - Ben Mazur (bmazur@sev.org)
 * Copyright (C) 2016-2025 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */
package megamek.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import megamek.common.loaders.MapSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * @author nderwin
 */
class MapSettingsTest {

    @TempDir
    private Path tempDirectory;

    @Test
    void panelOverridesCopyAndSerializeIndependently() throws Exception {
        MapSettings original = MapSettings.getInstance();
        original.setMapSize(2, 2);
        original.setBoardsSelectedVector(List.of("a", "b", "c", "d"));
        original.setBoardTheme(0, "volcano");
        original.setBoardTheme(3, "snow");
        original.setBoardLevelOffset(0, 5);
        original.setBoardLevelOffset(3, -7);
        original.setBoardStack(0, List.of("a", "alternate"));
        original.setBoardStack(3, List.of("d", MapSettings.BOARD_GENERATED));

        MapSettings copy = MapSettings.getInstance(original);
        copy.setBoardTheme(0, null);
        copy.setBoardTheme(1, "desert");
        copy.setBoardLevelOffset(0, 0);
        copy.setBoardLevelOffset(1, 3);
        copy.setBoardStack(0, List.of("a", "other"));
        assertEquals(List.of("a", "alternate"), original.getBoardStack(0));
        assertEquals(List.of("a", "other"), copy.getBoardStack(0));
        assertEquals("volcano", original.getBoardTheme(0));
        assertNull(original.getBoardTheme(1));
        assertNull(copy.getBoardTheme(0));
        assertEquals("snow", copy.getBoardTheme(3));
        assertEquals(5, original.getBoardLevelOffset(0));
        assertEquals(0, original.getBoardLevelOffset(1));
        assertEquals(0, copy.getBoardLevelOffset(0));
        assertEquals(-7, copy.getBoardLevelOffset(3));

        var bytes = new ByteArrayOutputStream();
        try (var output = new ObjectOutputStream(bytes)) {
            output.writeObject(original);
        }
        MapSettings received;
        try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            received = (MapSettings) input.readObject();
        }
        assertEquals(original.getBoardsSelectedVector(), received.getBoardsSelectedVector());
        assertEquals("volcano", received.getBoardTheme(0));
        assertNull(received.getBoardTheme(1));
        assertNull(received.getBoardTheme(2));
        assertEquals("snow", received.getBoardTheme(3));
        assertEquals(5, received.getBoardLevelOffset(0));
        assertEquals(0, received.getBoardLevelOffset(1));
        assertEquals(0, received.getBoardLevelOffset(2));
        assertEquals(-7, received.getBoardLevelOffset(3));
        assertEquals(original.getBoardStack(0), received.getBoardStack(0));
        assertEquals(original.getBoardStack(3), received.getBoardStack(3));
    }

    @Test
    void resizingKeepsPanelCoordinatesAndDiscardsCroppedOverrides() {
        MapSettings settings = MapSettings.getInstance();
        settings.setMapSize(2, 2);
        settings.setBoardsSelectedVector(List.of("a", "b", "c", "d"));
        settings.setBoardTheme(1, "volcano");
        settings.setBoardTheme(2, "snow");
        settings.setBoardTheme(3, "desert");
        settings.setBoardLevelOffset(1, 5);
        settings.setBoardLevelOffset(2, -3);
        settings.setBoardLevelOffset(3, 7);
        settings.setBoardStack(2, List.of("c", "alternate"));
        settings.setBoardStack(3, List.of("d", "other"));

        settings.setMapSize(3, 2);
        assertEquals("volcano", settings.getBoardTheme(1));
        assertNull(settings.getBoardTheme(2));
        assertEquals("snow", settings.getBoardTheme(3));
        assertEquals("desert", settings.getBoardTheme(4));
        assertEquals("c", settings.getBoardsSelectedVector().get(3));
        assertEquals(5, settings.getBoardLevelOffset(1));
        assertEquals(0, settings.getBoardLevelOffset(2));
        assertEquals(-3, settings.getBoardLevelOffset(3));
        assertEquals(7, settings.getBoardLevelOffset(4));
        assertEquals(List.of("c", "alternate"), settings.getBoardStack(3));
        assertEquals(List.of("d", "other"), settings.getBoardStack(4));

        settings.setMapSize(1, 2);
        assertNull(settings.getBoardTheme(0));
        assertEquals("snow", settings.getBoardTheme(1));
        assertNull(settings.getBoardTheme(4), "Removed preview indices are safe to query");
        assertEquals(0, settings.getBoardLevelOffset(0));
        assertEquals(-3, settings.getBoardLevelOffset(1));
        assertEquals(0, settings.getBoardLevelOffset(4));
        assertEquals(List.of("c", "alternate"), settings.getBoardStack(1));
        assertEquals(List.of(), settings.getBoardStack(4));
        settings.setMapSize(2, 2);
        assertNull(settings.getBoardTheme(1), "A restored panel must not inherit its cropped override");
        assertEquals("snow", settings.getBoardTheme(2));
        assertNull(settings.getBoardTheme(3));
        assertEquals(0, settings.getBoardLevelOffset(1));
        assertEquals(-3, settings.getBoardLevelOffset(2));
        assertEquals(0, settings.getBoardLevelOffset(3));
        assertEquals(List.of("c", "alternate"), settings.getBoardStack(2));
        assertEquals(List.of(), settings.getBoardStack(3), "Growing must not resurrect a cropped stack");
        settings.setMapSize(2, 1);
        settings.setMapSize(2, 2);
        assertNull(settings.getBoardTheme(2), "Cropped rows must not restore stale overrides either");
        assertEquals(0, settings.getBoardLevelOffset(2));
        assertEquals(List.of(), settings.getBoardStack(2));
    }

    @Test
    void serverValidationPrunesMissingCandidatesButKeepsGeneratedAndSurpriseMode() {
        MapSettings settings = MapSettings.getInstance();
        settings.setMapSize(2, 1);
        settings.setBoardsSelectedVector(List.of("missing", MapSettings.BOARD_SURPRISE + "missing\nkeep"));
        settings.setBoardStack(0, List.of("missing", "keep", MapSettings.BOARD_GENERATED));
        settings.setBoardsAvailableVector(List.of("keep"));
        settings.removeUnavailable();
        assertEquals("keep", settings.getBoardsSelectedVector().getFirst());
        assertEquals(List.of("keep", MapSettings.BOARD_GENERATED), settings.getBoardStack(0));
        assertEquals(MapSettings.BOARD_SURPRISE + "keep", settings.getBoardsSelectedVector().get(1));
        settings.chooseSurpriseBoards();
        assertEquals("keep", settings.getBoardsSelectedVector().get(1));
    }

    @Test
    void newSheetDimensionsAndSelectionSetsResetOverridesButPanelReplacementKeepsThem() {
        MapSettings settings = MapSettings.getInstance();
        settings.setBoardSize(16, 17);
        settings.setMapSize(1, 1);
        settings.setBoardsSelectedVector(List.of("a"));
        settings.setBoardTheme(0, "volcano");
        settings.setBoardLevelOffset(0, 5);
        settings.getBoardsSelectedVector().set(0, "b");
        assertEquals("volcano", settings.getBoardTheme(0));
        assertEquals(5, settings.getBoardLevelOffset(0));
        settings.setMapSize(1, 1);
        settings.setBoardSize(16, 17);
        assertEquals("volcano", settings.getBoardTheme(0));
        assertEquals(5, settings.getBoardLevelOffset(0));
        settings.setBoardSize(32, 17);
        assertNull(settings.getBoardTheme(0));
        assertEquals(0, settings.getBoardLevelOffset(0));
        settings.setBoardTheme(0, "snow");
        settings.setBoardLevelOffset(0, -5);
        settings.setBoardsSelectedVector(List.of("c"));
        assertNull(settings.getBoardTheme(0));
        assertEquals(0, settings.getBoardLevelOffset(0));
    }

    @Test
    void settingsWithoutSerializedOverridesRetainDefaultAndCanBeEdited() throws Exception {
        MapSettings settings = MapSettings.getInstance();
        settings.setMapSize(1, 1);
        // Java serialization supplies null for a field absent from older settings.
        var field = MapSettings.class.getDeclaredField("boardThemes");
        field.setAccessible(true);
        field.set(settings, null);
        var offsetField = MapSettings.class.getDeclaredField("boardLevelOffsets");
        offsetField.setAccessible(true);
        offsetField.set(settings, null);
        assertNull(settings.getBoardTheme(0));
        assertNull(MapSettings.getInstance(settings).getBoardTheme(0));
        assertEquals(0, settings.getBoardLevelOffset(0));
        assertEquals(0, MapSettings.getInstance(settings).getBoardLevelOffset(0));
        settings.setBoardLevelOffset(0, 5);
        assertEquals(5, settings.getBoardLevelOffset(0));
        settings.setBoardLevelOffset(0, 0);
        assertEquals(0, settings.getBoardLevelOffset(0));
        settings.setBoardTheme(0, "");
        assertEquals("", settings.getBoardTheme(0), "An explicit blank theme is distinct from Default");
        settings.setBoardTheme(0, null);
        assertNull(settings.getBoardTheme(0));
        assertThrows(IndexOutOfBoundsException.class, () -> settings.setBoardTheme(1, "snow"));
        assertThrows(IllegalArgumentException.class, () -> settings.setMapSize(0, 1));
    }

    @Test
    void panelLevelOffsetsAcceptInclusiveLimitsAndRejectValuesOutsideThem() {
        MapSettings settings = MapSettings.getInstance();
        settings.setMapSize(1, 1);
        settings.setBoardLevelOffset(0, -50);
        assertEquals(-50, settings.getBoardLevelOffset(0));
        settings.setBoardLevelOffset(0, 50);
        assertEquals(50, settings.getBoardLevelOffset(0));
        assertThrows(IllegalArgumentException.class, () -> settings.setBoardLevelOffset(0, -51));
        assertThrows(IllegalArgumentException.class, () -> settings.setBoardLevelOffset(0, 51));
        assertEquals(50, settings.getBoardLevelOffset(0), "Rejected values must leave the setting intact");
        assertThrows(IndexOutOfBoundsException.class, () -> settings.setBoardLevelOffset(1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> settings.setBoardLevelOffset(-1, 0));
    }

    @Test
    void testSaveAndLoad() throws IOException {
        assertTrue(Files.isDirectory(tempDirectory));
        final Path createdFilePath = Files.createFile(tempDirectory.resolve("test-map-settings.xml"));
        final File file = createdFilePath.toFile();

        MapSettings testMe = MapSettings.getInstance();
        try (OutputStream os = new FileOutputStream(file)) {
            testMe.setBoardSize(32, 34);
            testMe.save(os);
        }

        assertTrue(file.exists());
        assertTrue(file.length() > 0);

        try (InputStream is = new FileInputStream(file)) {
            testMe = MapSettings.getInstance(is);
        }

        assertEquals(32, testMe.getBoardWidth());
        assertEquals(34, testMe.getBoardHeight());
        assertEquals("", testMe.getTheme());
        assertEquals(0, testMe.getInvertNegativeTerrain());
        assertEquals(40, testMe.getHilliness());
        assertEquals(5, testMe.getRange());
        assertEquals(5, testMe.getProbInvert());
        assertEquals(0, testMe.getAlgorithmToUse());
        assertEquals(0, testMe.getCliffs());
        assertEquals(3, testMe.getMinForestSpots());
        assertEquals(8, testMe.getMaxForestSpots());
        assertEquals(4, testMe.getMinForestSize());
        assertEquals(12, testMe.getMaxForestSize());
        assertEquals(30, testMe.getProbHeavy());
        assertEquals(0, testMe.getProbUltra());
        assertEquals(2, testMe.getMinRoughSpots());
        assertEquals(10, testMe.getMaxRoughSpots());
        assertEquals(1, testMe.getMinRoughSize());
        assertEquals(2, testMe.getMaxRoughSize());
        assertEquals(2, testMe.getMinSandSpots());
        assertEquals(10, testMe.getMaxSandSpots());
        assertEquals(1, testMe.getMinSandSize());
        assertEquals(2, testMe.getMaxSandSize());
        assertEquals(0, testMe.getMinSnowSize());
        assertEquals(0, testMe.getMaxSnowSize());
        assertEquals(0, testMe.getMinSnowSpots());
        assertEquals(0, testMe.getMaxSnowSpots());
        assertEquals(2, testMe.getMinPlantedFieldSpots());
        assertEquals(10, testMe.getMaxPlantedFieldSpots());
        assertEquals(1, testMe.getMinPlantedFieldSize());
        assertEquals(2, testMe.getMaxPlantedFieldSize());
        assertEquals(2, testMe.getMinSwampSpots());
        assertEquals(10, testMe.getMaxSwampSpots());
        assertEquals(1, testMe.getMinSwampSize());
        assertEquals(2, testMe.getMaxSwampSize());
        assertEquals(0, testMe.getProbRoad());
        assertEquals(1, testMe.getMinWaterSpots());
        assertEquals(3, testMe.getMaxWaterSpots());
        assertEquals(5, testMe.getMinWaterSize());
        assertEquals(10, testMe.getMaxWaterSize());
        assertEquals(33, testMe.getProbDeep());
        assertEquals(0, testMe.getProbRiver());
        assertEquals(1, testMe.getMinCraters());
        assertEquals(2, testMe.getMaxCraters());
        assertEquals(2, testMe.getMinRadius());
        assertEquals(7, testMe.getMaxRadius());
        assertEquals(0, testMe.getProbCrater());
        assertEquals(0, testMe.getMinPavementSpots());
        assertEquals(0, testMe.getMaxPavementSpots());
        assertEquals(1, testMe.getMinPavementSize());
        assertEquals(6, testMe.getMaxPavementSize());
        assertEquals(0, testMe.getMinRubbleSpots());
        assertEquals(0, testMe.getMaxRubbleSpots());
        assertEquals(1, testMe.getMinRubbleSize());
        assertEquals(6, testMe.getMaxRubbleSize());
        assertEquals(0, testMe.getMinFortifiedSpots());
        assertEquals(0, testMe.getMaxFortifiedSpots());
        assertEquals(1, testMe.getMinFortifiedSize());
        assertEquals(2, testMe.getMaxFortifiedSize());
        assertEquals(0, testMe.getMinIceSpots());
        assertEquals(0, testMe.getMaxIceSpots());
        assertEquals(1, testMe.getMinIceSize());
        assertEquals(6, testMe.getMaxIceSize());
        assertEquals(0, testMe.getFxMod());
        assertEquals(0, testMe.getProbFreeze());
        assertEquals(0, testMe.getProbFlood());
        assertEquals(0, testMe.getProbForestFire());
        assertEquals(0, testMe.getProbDrought());
        assertEquals("NONE", testMe.getCityType());
        assertEquals(16, testMe.getCityBlocks());
        assertEquals(75, testMe.getCityDensity());
        assertEquals(10, testMe.getCityMinCF());
        assertEquals(100, testMe.getCityMaxCF());
        assertEquals(1, testMe.getCityMinFloors());
        assertEquals(6, testMe.getCityMaxFloors());
        assertEquals(60, testMe.getTownSize());
        assertEquals(0, testMe.getMountainPeaks());
        assertEquals(7, testMe.getMountainWidthMin());
        assertEquals(20, testMe.getMountainWidthMax());
        assertEquals(5, testMe.getMountainHeightMin());
        assertEquals(8, testMe.getMountainHeightMax());
        assertEquals(0, testMe.getMountainStyle());
    }
}
