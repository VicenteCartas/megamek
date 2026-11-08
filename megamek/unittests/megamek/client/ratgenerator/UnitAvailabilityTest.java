/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ratgenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The availability table's eras, trimmed to those with data, and its texts. */
class UnitAvailabilityTest {
    private static final Map<String, Map<String, String>> TEXTS = Map.of(
          "Succession Wars", Map.of("FS", "Common"),
          "Clan Invasion", Map.of("LA", "Rare\n+1"),
          "Jihad", Map.of("FS", "Uncommon", "LA", "Rare"));

    private static UnitAvailability<String> table(List<String> eras) {
        return UnitAvailability.of(eras, List.of("FS", "LA"),
              (era, faction) -> TEXTS.getOrDefault(era, Map.of()).get(faction));
    }

    @Test
    void theErasRunFromTheFirstToTheLastWithData() {
        UnitAvailability<String> table = table(List.of("Star League", "Succession Wars", "Renaissance",
              "Clan Invasion", "Jihad", "Dark Age"));
        assertEquals(List.of("Succession Wars", "Renaissance", "Clan Invasion", "Jihad"), table.eras(),
              "an era between two with data stays; those before and after go");
        assertEquals(List.of("FS", "LA"), table.factions());
    }

    @Test
    void aFactionWithoutTheUnitHasNoText() {
        UnitAvailability<String> table = table(List.of("Succession Wars", "Renaissance", "Clan Invasion"));
        assertEquals("Common", table.text("Succession Wars", "FS"));
        assertEquals("", table.text("Succession Wars", "LA"));
        assertEquals("", table.text("Renaissance", "FS"));
        assertEquals("Rare\n+1", table.text("Clan Invasion", "LA"));
        assertEquals("", table.text("Dark Age", "FS"), "an era not shown has no text");
    }

    @Test
    void noDataShowsNoEras() {
        assertTrue(table(List.of("Star League", "Dark Age")).eras().isEmpty());
        assertTrue(UnitAvailability.of(List.<String>of(), List.of("FS"), (era, faction) -> "Common").eras()
              .isEmpty());
    }
}
