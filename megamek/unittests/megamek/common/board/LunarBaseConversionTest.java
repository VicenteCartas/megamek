/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A legacy lunar base, converted as a game loads it: its buildings take the lunar designs (BoardSceneryLayouts). */
class LunarBaseConversionTest {
    private static final String GLAZED = "building:3:63;bldg_elev:2;bldg_cf:90;fluff:6", PLAIN = "building:3:63;bldg_elev:2;bldg_cf:90";

    @Test
    void roundGlazedBuildingsBecomeDomesAndTheRestHabitats(@TempDir Path folder) throws IOException {
        Map<Coords, String> terrain = new HashMap<>();
        // A seven-hex flower under skylights, a nineteen-hex disc under skylights, and a two-hex building without.
        disc(terrain, new Coords(3, 3), 1, GLAZED);
        disc(terrain, new Coords(10, 6), 2, GLAZED);
        terrain.put(new Coords(2, 9), PLAIN);
        terrain.put(new Coords(3, 9), PLAIN);
        Board board = legacy(folder, terrain, 14, 11);
        assertEquals(BoardSceneryLayouts.GLASS_DOME, design(board, 3, 3));
        assertEquals(BoardSceneryLayouts.GLASS_DOME, design(board, 3, 4), "Every hex of a building takes its design");
        assertEquals(BoardSceneryLayouts.BIODOME, design(board, 10, 6));
        assertEquals(BoardSceneryLayouts.LUNAR_HABITAT, design(board, 2, 9));
        assertTrue(board.getHex(3, 3).getDecorations().stream().noneMatch(object -> object.asset().startsWith("scenery/roofs/")),
              "A dome is its own glass: the skylights it replaces go");
        assertTrue(board.getHex(3, 3).containsTerrain(megamek.common.units.Terrains.FLUFF),
              "The skylight token stays for the 2D board");
    }

    private static void disc(Map<Coords, String> terrain, Coords centre, int rings, String hex) {
        for (int x = centre.getX() - rings; x <= centre.getX() + rings; x++) {
            for (int y = centre.getY() - rings - 1; y <= centre.getY() + rings + 1; y++) {
                Coords at = new Coords(x, y);
                if (at.distance(centre) <= rings) { terrain.put(at, hex); }
            }
        }
    }

    /** A legacy .board of the lunar theme with {@code terrain} on its hexes, loaded and converted as a game loads it. */
    private static Board legacy(Path folder, Map<Coords, String> terrain, int width, int height) throws IOException {
        StringBuilder text = new StringBuilder("size " + width + " " + height + "\n");
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                text.append(String.format(Locale.ROOT, "hex %02d%02d 0 \"%s\" \"lunar\"%n", x + 1, y + 1,
                      terrain.getOrDefault(new Coords(x, y), "")));
            }
        }
        text.append("end\n");
        Path file = folder.resolve("lunar.board");
        Files.writeString(file, text);
        return BoardFile.loadSheet(file.toFile(), false, true);
    }

    private static String design(Board board, int x, int y) {
        var appearance = board.getHex(x, y).getAppearance().get("building");
        return appearance == null ? null : appearance.variant();
    }
}
