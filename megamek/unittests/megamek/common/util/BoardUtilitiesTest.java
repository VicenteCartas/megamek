/*
 * Copyright (C) 2014-2026 The MegaMek Team. All Rights Reserved.
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
package megamek.common.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.planetaryConditions.Weather;
import megamek.common.planetaryConditions.Wind;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

/**
 * @author Deric Page (deric.page@nisc.coop) (ext 2335)
 * @since 9/3/14 1:44 PM
 */
class BoardUtilitiesTest {
    /** A flipped sheet's separated sides flip with it, and a pavement's Edges stay. */
    @Test void flippingABoardFlipsSeparatedSides() {
        Board board = Board.createEmptyBoard(4, 4);
        Hex hex = new Hex(0, "pavement:1", "");
        hex.setAppearance(java.util.Map.of("pavement", HexAppearance.withSeparated(HexAppearance.EDGES_HEX, 1)));
        board.setHex(1, 1, hex);
        BoardUtilities.flip(board, false, true);
        var flipped = board.getHex(1, 2).getAppearance();
        assertEquals(8, HexAppearance.separated(flipped, "pavement"));
        assertEquals(HexAppearance.Edges.HEX, HexAppearance.edges(flipped));
    }


    /**
     * Moderate rain turns the clear ground ({@link Hex#isClear}), depth-0 water and dirt roads to mud (TO:AR p.57): a
     * natural bridge at the hex's level is clear ground, a built one is not, nor is a paved road; the ground under a
     * raised built bridge and a hex beside a slope are.
     */
    @Test
    void moderateRainMudsTheClearGround() {
        Board board = Board.createEmptyBoard(7, 1);
        board.setHex(new Coords(0, 0), new Hex(0, "bridge:1:09;bridge_cf:40;bridge_elev:0", ""));
        board.getHex(0, 0).setAppearance(Map.of("bridge", HexAppearance.NATURAL_BRIDGE));
        board.setHex(new Coords(1, 0), new Hex(0, "bridge:1:09;bridge_cf:40;bridge_elev:0", ""));
        board.setHex(new Coords(2, 0), new Hex(0, "road:1:09", ""));
        board.setHex(new Coords(3, 0), new Hex(0, "road:3:09", ""));
        board.setHex(new Coords(4, 0), new Hex(0, "bridge:1:09;bridge_cf:40;bridge_elev:2", ""));
        board.setHex(new Coords(5, 0), new Hex(0, "water:0", ""));
        board.setHex(new Coords(6, 0), new Hex(0, "fluff:4", ""));
        // The incline markers a slope (and the GUI preference) adds; set after the hexes, which recompute them
        board.getHex(5, 0).addTerrain(new Terrain(Terrains.INCLINE_BOTTOM, 1, true, 2));
        board.getHex(6, 0).addTerrain(new Terrain(Terrains.INCLINE_TOP, 1, true, 1));

        BoardUtilities.addWeatherConditions(board, Weather.MOD_RAIN, Wind.CALM);

        assertTrue(board.getHex(0, 0).containsTerrain(Terrains.MUD), "natural bridge at the hex's level");
        assertFalse(board.getHex(1, 0).containsTerrain(Terrains.MUD), "built bridge at the hex's level");
        assertFalse(board.getHex(2, 0).containsTerrain(Terrains.MUD), "paved road");
        assertTrue(board.getHex(3, 0).containsTerrain(Terrains.MUD), "dirt road");
        assertTrue(board.getHex(4, 0).containsTerrain(Terrains.MUD), "the ground under a raised built bridge");
        assertTrue(board.getHex(5, 0).containsTerrain(Terrains.MUD), "depth-0 water beside a slope");
        assertFalse(board.getHex(5, 0).containsTerrain(Terrains.WATER), "the mud replaces the shallow water");
        assertTrue(board.getHex(6, 0).containsTerrain(Terrains.MUD), "an art token on a slope");
    }

    /**
     * Every rain that muds clear ground muds a CLEAR raised deck ({@link Hex#isClearDeck}), whatever lies beneath; a
     * built deck, a deck carrying a road and a deck at the hex's level (its mud is the ground's) stay dry.
     */
    @Test
    void rainMudsClearRaisedDecks() {
        for (Weather weather : List.of(Weather.MOD_RAIN, Weather.HEAVY_RAIN, Weather.DOWNPOUR, Weather.LIGHT_RAIN)) {
            Board board = Board.createEmptyBoard(4, 1);
            board.setHex(new Coords(0, 0), new Hex(0, "water:1;bridge:1:09;bridge_cf:40;bridge_elev:2", ""));
            board.setHex(new Coords(1, 0), new Hex(0, "bridge:1:09;bridge_cf:40;bridge_elev:2", ""));
            board.setHex(new Coords(2, 0), new Hex(0, "bridge:1:09;bridge_cf:40;bridge_elev:2;bridge_road:1:9", ""));
            board.setHex(new Coords(3, 0), new Hex(0, "bridge:1:09;bridge_cf:40;bridge_elev:0", ""));
            for (int x : new int[] { 0, 2, 3 }) {
                board.getHex(x, 0).setAppearance(Map.of("bridge", HexAppearance.NATURAL_BRIDGE));
            }

            BoardUtilities.addWeatherConditions(board, weather, Wind.CALM);

            assertEquals(weather != Weather.LIGHT_RAIN, board.getHex(0, 0).containsTerrain(Terrains.BRIDGE_MUD),
                  weather + ": natural deck over water");
            assertFalse(board.getHex(1, 0).containsTerrain(Terrains.BRIDGE_MUD), weather + ": built deck");
            assertFalse(board.getHex(2, 0).containsTerrain(Terrains.BRIDGE_MUD), weather + ": deck carrying a road");
            assertFalse(board.getHex(3, 0).containsTerrain(Terrains.BRIDGE_MUD), weather + ": deck at the hex's level");
        }
    }

    @Test
    void combiningRepeatedSheetsCopiesDecorationsAndKeepsTheirMetadata() {
        Board source = Board.createEmptyBoard(2, 2);
        source.setNativeFormat(true); source.setSourceHeader("# Original author"); source.addTag("test");
        var at = new megamek.common.board.Coords(0, 0);
        source.setAnnotations(at, java.util.List.of("annotation"));
        var object = new megamek.common.board.BoardDecoration("car", "prop", "car", null, .2, -.1, 30,
              false, .8, megamek.common.board.BoardDecoration.Placement.absolute(4), 0);
        source.getHex(at).setDecorations(java.util.List.of(object));
        Board result = BoardUtilities.combine(2, 2, 2, 1, new Board[] { source, source }, megamek.common.loaders.MapSettings.MEDIUM_GROUND);
        assertNotSame(source.getHex(at), result.getHex(at));
        var instances = java.util.List.of(result.getHex(0, 0).getDecorations().getFirst(), result.getHex(2, 0).getDecorations().getFirst());
        assertEquals(2, instances.stream().map(megamek.common.board.BoardDecoration::id).distinct().count());
        assertEquals(object.placement(), instances.get(1).placement());
        assertEquals(java.util.List.of("annotation"), result.getAnnotations(new megamek.common.board.Coords(2, 0)));
        assertEquals(source.getSourceHeader(), result.getSourceHeader());
        assertTrue(result.isNativeFormat());
        assertTrue(result.getTags().contains("test"));
        result.getHex(at).setDecorations(java.util.List.of());
        assertEquals(java.util.List.of(object), source.getHex(at).getDecorations());
    }

    @Test
    void combiningSheetsKeepsEachSheetsGroupsApart() {
        var ground = megamek.common.board.BoardDecoration.Placement.ground();
        Board first = Board.createEmptyBoard(2, 2), second = Board.createEmptyBoard(2, 2);
        // Imported ids are deterministic per hex, so two sheets can share some ids and group names but not others.
        first.getHex(0, 0).setDecorations(java.util.List.of(
              new megamek.common.board.BoardDecoration("L1_1_0", "prop", "car", null, 0, 0, 0, false, 1, ground, 0).withGroup("L1_1_g0"),
              new megamek.common.board.BoardDecoration("L1_1_1", "prop", "car", null, .2, 0, 0, false, 1, ground, 0).withGroup("L1_1_g0")));
        second.getHex(0, 0).setDecorations(java.util.List.of(
              new megamek.common.board.BoardDecoration("L1_1_0", "prop", "car", null, 0, 0, 0, false, 1, ground, 0).withGroup("L1_1_g0"),
              new megamek.common.board.BoardDecoration("L1_1_5", "prop", "car", null, .2, 0, 0, false, 1, ground, 0).withGroup("L1_1_g0"),
              new megamek.common.board.BoardDecoration("L1_1_6", "prop", "car", null, -.2, 0, 0, false, 1, ground, 0)));
        Board result = BoardUtilities.combine(2, 2, 3, 1, new Board[] { first, first, second },
              megamek.common.loaders.MapSettings.MEDIUM_GROUND);
        var sheets = java.util.List.of(result.getHex(0, 0).getDecorations(), result.getHex(2, 0).getDecorations(),
              result.getHex(4, 0).getDecorations());
        for (var sheet : sheets) {
            assertEquals(1, sheet.stream().map(megamek.common.board.BoardDecoration::group)
                  .filter(java.util.Objects::nonNull).distinct().count(), "Each sheet keeps its members together");
        }
        assertEquals(3, sheets.stream().map(sheet -> sheet.getFirst().group()).distinct().count(),
              "Repeated and different sheets never share a group");
        assertEquals(7, sheets.stream().flatMap(java.util.List::stream).map(megamek.common.board.BoardDecoration::id)
              .distinct().count());
        assertNull(sheets.get(2).get(2).group());
        assertEquals("L1_1_g0", first.getHex(0, 0).getDecorations().getFirst().group(), "Sources are untouched");
    }

    @Test
    void flipsTransformCenterObjectsWithoutLosingNorthSouthEdgesOrLegacyDesigns() {
        Board board = Board.createEmptyBoard(3, 3);
        var at = new megamek.common.board.Coords(1, 1);
        var hex = new megamek.common.Hex(0, "road:1:9;building:1:65;bldg_cf:15;bldg_elev:1", "");
        var object = new megamek.common.board.BoardDecoration("tree", "prop", "vegetation/tree", null, .2, -.1, 30,
              false, .8, megamek.common.board.BoardDecoration.Placement.ground(), 0).withGroup("grove");
        hex.setDecorations(java.util.List.of(object)); board.setHex(at, hex);
        BoardUtilities.flip(board, true, false);
        var flipped = board.getHex(at).getDecorations().getFirst();
        assertEquals(-.2, flipped.x()); assertEquals(-30, flipped.rotation()); assertTrue(flipped.mirror());
        assertEquals("grove", flipped.group());
        assertEquals(9, board.getHex(at).getTerrain(megamek.common.units.Terrains.ROAD).getExits());
        assertEquals(65, board.getHex(at).getTerrain(megamek.common.units.Terrains.BUILDING).getExits());
        BoardUtilities.flip(board, true, false);
        assertEquals(object, board.getHex(at).getDecorations().getFirst());
    }

    @Test
    void testCraterProfile() {
        int craterRadius = 8;
        int maxDepth = 4;

        // Start at the center;
        int distanceFromCenter = 0;
        int expected = -4;
        assertEquals(expected, BoardUtilities.craterProfile(distanceFromCenter, craterRadius, maxDepth));

        // One hex from center;
        distanceFromCenter = 1;
        assertEquals(expected, BoardUtilities.craterProfile(distanceFromCenter, craterRadius, maxDepth));

        // Three hexes from center;
        distanceFromCenter = 3;
        assertEquals(expected, BoardUtilities.craterProfile(distanceFromCenter, craterRadius, maxDepth));

        // Four hexes from center;
        distanceFromCenter = 4;
        assertEquals(expected, BoardUtilities.craterProfile(distanceFromCenter, craterRadius, maxDepth));

        // Five hexes from center;
        distanceFromCenter = 5;
        expected = -3;
        assertEquals(expected, BoardUtilities.craterProfile(distanceFromCenter, craterRadius, maxDepth));

        // Six hexes from center;
        distanceFromCenter = 6;
        assertEquals(expected, BoardUtilities.craterProfile(distanceFromCenter, craterRadius, maxDepth));

        // Seven hexes from center;
        distanceFromCenter = 7;
        expected = -2;
        assertEquals(expected, BoardUtilities.craterProfile(distanceFromCenter, craterRadius, maxDepth));

        // Eight hexes from center;
        distanceFromCenter = 8;
        expected = 0;
        assertEquals(expected, BoardUtilities.craterProfile(distanceFromCenter, craterRadius, maxDepth));
    }

    /**
     * Crater radius bounds come from unvalidated client-supplied map settings. An inverted range
     * (max below min) must not reach {@link megamek.common.compute.Compute#randomInt(int)}, which rejects a
     * bound of zero or less, nor the crater-profile array sizing.
     */
    @Test
    void addCratersToleratesAnInvertedRadiusRange() {
        Board board = emptyBoard();

        assertDoesNotThrow(() -> BoardUtilities.addCraters(board, 6, 2, 1, 1));
    }

    /** A negative minimum radius must not produce a negative crater-profile array size either. */
    @Test
    void addCratersToleratesANegativeRadiusRange() {
        Board board = emptyBoard();

        assertDoesNotThrow(() -> BoardUtilities.addCraters(board, -5, -2, 1, 1));
    }

    /**
     * Resize moves every hex by the old editor's rule: an odd west change also moves the odd columns a row down, so every
     * hex keeps its neighbours in every direction. The copy keeps terrain, objects, metadata and notes; the source board
     * stays as it was.
     */
    @Test
    void resizeKeepsEveryNeighbourTheContentAndTheMetadata() {
        var at = new megamek.common.board.Coords(2, 2);
        var note = new megamek.common.board.Coords(3, 1);
        var car = new megamek.common.board.BoardDecoration("car", "prop", "car", null, .2, 0, 0, false, 1,
              megamek.common.board.BoardDecoration.Placement.ground(), 0).withGroup("g");
        Board board = Board.createEmptyBoard(6, 5);
        board.setHex(at, new megamek.common.Hex(3, "woods:1;foliage_elev:2", ""));
        board.getHex(at).setDecorations(java.util.List.of(car));
        board.addTag("Arena"); board.setRoadsAutoExit(false); board.setDescription("Ridge");
        board.setAnnotations(note, java.util.List.of("Note"));
        for (int west : new int[] { 1, -1, 2, -3 }) {
            Board resized = BoardUtilities.resize(board, 1, 0, 2, west);
            assertEquals(6 + west, resized.getWidth());
            assertEquals(8, resized.getHeight());
            for (int x = 0; x < board.getWidth(); x++) {
                for (int y = 0; y < board.getHeight(); y++) {
                    var from = new megamek.common.board.Coords(x, y);
                    for (int direction = 0; direction < 6; direction++) {
                        assertEquals(BoardUtilities.resized(from.translated(direction), 1, west),
                              BoardUtilities.resized(from, 1, west).translated(direction), from + " towards " + direction);
                    }
                }
            }
            var moved = BoardUtilities.resized(at, 1, west);
            if (resized.contains(moved)) {
                assertEquals(3, resized.getHex(moved).getLevel());
                assertTrue(resized.getHex(moved).containsTerrain(megamek.common.units.Terrains.WOODS));
                assertEquals(java.util.List.of(car), resized.getHex(moved).getDecorations());
            }
            assertEquals(java.util.Set.of("Arena"), resized.getTags());
            assertFalse(resized.getRoadsAutoExit());
            assertEquals("Ridge", resized.getDescription());
            var movedNote = BoardUtilities.resized(note, 1, west);
            assertEquals(resized.contains(movedNote) ? java.util.Map.of(movedNote, java.util.List.of("Note")) : java.util.Map.of(),
                  resized.getAnnotations().entrySet().stream().collect(java.util.stream.Collectors.toMap(
                        java.util.Map.Entry::getKey, entry -> java.util.List.copyOf(entry.getValue()))));
        }
        // An odd west change without a new south row drops the lowest hex of each odd column.
        board.setHex(1, 3, new megamek.common.Hex(6)); board.setHex(1, 4, new megamek.common.Hex(7));
        Board shifted = BoardUtilities.resize(board, 0, 0, 0, 1);
        assertEquals(6, shifted.getHex(2, 4).getLevel());
        for (int x = 0; x < shifted.getWidth(); x++) {
            for (int y = 0; y < shifted.getHeight(); y++) { assertTrue(shifted.getHex(x, y).getLevel() != 7); }
        }
        Board cut = BoardUtilities.resize(board, -3, -4, 0, 0);
        assertEquals(2, cut.getWidth());
        assertEquals(2, cut.getHeight());
        assertTrue(cut.getAnnotations().isEmpty());
        assertEquals(6, board.getWidth());
        assertEquals(java.util.List.of(car), board.getHex(at).getDecorations());
        assertThrows(IllegalArgumentException.class, () -> BoardUtilities.resize(board, 0, -6, 0, 0));
    }

    /** @return a small blank board that craters can be placed on */
    private static Board emptyBoard() {
        Board board = new Board();
        board.load("""
              size 5 5
              hex 0101 0 "" ""
              end""", null);
        return board;
    }
}
