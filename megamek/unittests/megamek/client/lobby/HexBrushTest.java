/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.board.HexEditSpec;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

/**
 * The Game Master's terrain brush: what it starts from, what a click paints, and the edit it sends.
 *
 * <p>The brush opens on a hex the Game Master right-clicked, and is meant to start from what that hex actually holds -
 * including how far the terrain has already been knocked down. Woods shelled to a terrain factor of 15 that open
 * reading 50 invite a Game Master to press Apply and quietly restore them.</p>
 */
class HexBrushTest {

    private static final int BOARD = 0;
    private static final int SIZE = 10;
    private static final int WATER_INDEX = 1;

    private final Map<Coords, Hex> hexes = new HashMap<>();
    private final Set<Coords> occupied = new HashSet<>();
    private final List<HexEditSpec> sent = new ArrayList<>();
    private Collection<Coords> marked = List.of();
    private final HexBrush.Host host = new HexBrush.Host() {
        @Override
        public Hex hex(Coords coords) {
            return hexes.get(coords);
        }

        @Override
        public boolean occupied(Coords coords) {
            return occupied.contains(coords);
        }

        @Override
        public void sendHexEdit(HexEditSpec spec) {
            sent.add(spec);
        }

        @Override
        public void showMarks(Collection<Coords> painted) {
            marked = List.copyOf(painted);
        }
    };

    HexBrushTest() {
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                hexes.put(new Coords(x, y), new Hex());
            }
        }
    }

    /** @return a hex holding the given terrain at the given level, at the factor the rules give it when new */
    private static Hex hexHolding(int terrainType, int level) {
        Hex hex = new Hex();
        hex.addTerrain(new Terrain(terrainType, level));
        return hex;
    }

    @Test
    void theBrushStartsFromTheTerrainTheHexHolds() {
        Terrain starting = HexBrush.brushableTerrainIn(hexHolding(Terrains.WOODS, 1));

        assertNotNull(starting, "a hex of woods has something for the brush to start from");
        assertEquals(Terrains.WOODS, starting.getType(), "the brush should start on woods");
        assertEquals(1, starting.getLevel(), "at the level the hex holds them");
    }

    @Test
    void theBrushStartsFromTheTerrainFactorTheHexHolds() {
        // light woods knocked down to 15 once opened reading 50, the value the rules give new light woods, so applying
        // without touching anything put the woods back up again
        Hex woods = hexHolding(Terrains.WOODS, 1);
        woods.getTerrain(Terrains.WOODS).setTerrainFactor(15);
        Coords coords = new Coords(4, 4);
        hexes.put(coords, woods);

        assertEquals(15, HexBrush.brushableTerrainIn(woods).getTerrainFactor());
        HexBrush brush = new HexBrush(BOARD, coords, host);
        assertEquals(15, brush.factor(), "the brush should start from what is left of the woods");
        assertTrue(brush.factorEnabled());
    }

    @Test
    void aHexHoldingNothingTheBrushPaintsStartsFromNothing() {
        assertNull(HexBrush.brushableTerrainIn(new Hex()),
              "bare ground gives the brush nothing to start from, so it stays on its own first choice");
    }

    @Test
    void aStructureIsNotSomethingTheBrushStartsFrom() {
        Hex hex = new Hex();
        hex.addTerrain(new Terrain(Terrains.BUILDING, 2));
        hex.addTerrain(new Terrain(Terrains.BLDG_CF, 40));

        assertNull(HexBrush.brushableTerrainIn(hex),
              "a building is changed in its own card, so the terrain brush does not open on it");
    }

    @Test
    void waterIsSomethingTheBrushStartsFrom() {
        Terrain starting = HexBrush.brushableTerrainIn(hexHolding(Terrains.WATER, 2));

        assertNotNull(starting, "water is terrain the brush paints");
        assertEquals(2, starting.getLevel(), "and it should start at the depth the hex holds");
    }

    @Test
    void opensWithTheHexPaintedAndFollowsTheBrushUntilAnotherHexIsPainted() {
        Coords opened = new Coords(4, 4);
        hexes.put(opened, hexHolding(Terrains.WOODS, 1));
        HexBrush brush = new HexBrush(BOARD, opened, host);

        assertEquals(List.of(opened), brush.painted());
        assertEquals(List.of(opened), marked, "the painted hexes are marked on the board");

        brush.terrainIndex(0);
        brush.apply();
        assertTrue(sent.get(0).getPaintedHexes().get(opened).isBareGround(),
              "the hex opened on follows the brush, so picking bare ground does not send woods");
    }

    @Test
    void aClickPaintsAndTheSameClickOrTheEraserLiftsItBack() {
        HexBrush brush = new HexBrush(BOARD, new Coords(0, 0), host);
        Coords clicked = new Coords(5, 5);

        brush.brushHex(clicked);
        assertTrue(brush.painted().contains(clicked));
        brush.brushHex(clicked);
        assertFalse(brush.painted().contains(clicked), "the same stroke again takes it back");

        brush.brushHex(clicked);
        brush.erasing(true);
        brush.brushHex(clicked);
        assertFalse(brush.painted().contains(clicked));

        brush.erasing(false);
        brush.sevenHexes(true);
        brush.brushHex(clicked);
        assertEquals(8, brush.painted().size(), "the wide brush paints the hex and its six neighbours");
        brush.undoLastHex();
        assertEquals(7, brush.painted().size());
        brush.clear();
        assertTrue(brush.painted().isEmpty());
        assertFalse(brush.mayApply());
    }

    @Test
    void appliesThePaintedHexesAndMayTakeThemBack() {
        HexBrush brush = new HexBrush(BOARD, new Coords(2, 2), host);
        brush.terrainIndex(WATER_INDEX);
        brush.brushHex(new Coords(3, 3));
        brush.changeGround(true);
        brush.ground(2);
        brush.brushAll();

        assertTrue(brush.mayApply());
        brush.apply();

        assertEquals(1, sent.size());
        HexEditSpec spec = sent.get(0);
        assertEquals(BOARD, spec.getBoardId());
        assertEquals(Set.of(new Coords(2, 2), new Coords(3, 3)), spec.getCoords());
        HexEditSpec.HexPaint paint = spec.getPaintedHexes().get(new Coords(3, 3));
        assertEquals(2, paint.getLevel());
        assertEquals(1, paint.getTerrainLevels().get(Terrains.WATER), "water starts on its first real depth");
        assertTrue(brush.mayUndo());

        brush.undo();
        assertTrue(sent.get(1).isUndoingLastEdit());
        assertFalse(brush.mayUndo());
    }

    @Test
    void theGroundIsLeftAloneUnlessAskedFor() {
        HexBrush brush = new HexBrush(BOARD, new Coords(2, 2), host);
        brush.ground(5);
        brush.apply();

        assertNull(sent.get(0).getPaintedHexes().get(new Coords(2, 2)).getLevel());
    }

    @Test
    void refusesWaterUnderStandingUnits() {
        Coords standing = new Coords(6, 6);
        occupied.add(standing);
        HexBrush brush = new HexBrush(BOARD, standing, host);
        brush.terrainIndex(WATER_INDEX);

        assertFalse(brush.mayApply());
        assertTrue(brush.legality().contains(standing.getBoardNum()));
        brush.apply();
        assertTrue(sent.isEmpty());
    }

    @Test
    void paintsNothingOffTheBoard() {
        HexBrush brush = new HexBrush(BOARD, new Coords(0, 0), host);

        brush.brushHex(new Coords(SIZE + 5, SIZE + 5));

        assertEquals(List.of(new Coords(0, 0)), brush.painted());
    }

    @Test
    void theViewHoldsWhatThePanelDrawsAndKeepsItWhileTheBrushChanges() {
        Coords opened = new Coords(4, 4);
        Hex woods = hexHolding(Terrains.WOODS, 1);
        woods.getTerrain(Terrains.WOODS).setTerrainFactor(15);
        hexes.put(opened, woods);
        HexBrush brush = new HexBrush(BOARD, opened, host);

        HexBrush.View view = brush.view();
        assertEquals(brush.terrainIndex(), view.terrainIndex());
        assertEquals(brush.levelNames(), view.levelNames());
        assertEquals(brush.levelIndex(), view.levelIndex());
        assertTrue(view.factorEnabled());
        assertEquals(15, view.factor());
        assertEquals(brush.factorMax(), view.factorMax());
        assertEquals(brush.bookFactorText(), view.bookFactorText());
        assertEquals(brush.paintedEntries(), view.paintedEntries());
        assertTrue(view.anyPainted());
        assertEquals(brush.groundText(), view.groundText());
        assertEquals(brush.legality(), view.legality());
        assertTrue(view.mayApply());
        assertFalse(view.mayUndo());

        brush.terrainIndex(0);
        brush.clear();
        brush.changeGround(true);
        brush.ground(3);
        brush.sevenHexes(true);
        brush.erasing(true);
        assertEquals(15, view.factor(), "a view already handed out does not follow the brush");
        assertTrue(view.anyPainted());

        HexBrush.View after = brush.view();
        assertEquals(0, after.terrainIndex());
        assertTrue(after.levelNames().isEmpty());
        assertNull(after.bookFactorText());
        assertFalse(after.factorEnabled());
        assertTrue(after.changeGround());
        assertEquals(3, after.ground());
        assertTrue(after.sevenHexes());
        assertTrue(after.erasing());
        assertFalse(after.anyPainted());
        assertFalse(after.mayApply());
    }

    @Test
    void theViewReadsTheBoardAsItStandsWhenMade() {
        Coords hex = new Coords(6, 6);
        HexBrush brush = new HexBrush(BOARD, hex, host);
        brush.terrainIndex(WATER_INDEX);
        assertTrue(brush.view().mayApply());

        occupied.add(hex);

        assertFalse(brush.view().mayApply(), "a unit moving in is seen by the next view");
    }
}
