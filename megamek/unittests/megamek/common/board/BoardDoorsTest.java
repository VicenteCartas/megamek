/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import megamek.common.board.BoardDoors.Door;
import org.junit.jupiter.api.Test;

/** A building's large doors as one opening or apart: which may be linked, and what linking and parting leave. */
class BoardDoorsTest {
    private static final Coords HEX = new Coords(4, 4), SOUTH = HEX.translated(3);
    /** Along the east wall of a building of two hexes, one above the other: three doors that meet corner to corner. */
    private static final Door UPPER = new Door(HEX, 1), MIDDLE = new Door(HEX, 2), LOWER = new Door(SOUTH, 1);

    /** A plan of the two hexes with a door two levels high on each of {@code doors}. */
    private static BoardDoors.Plan plan(Door... doors) {
        Map<Coords, Map<String, HexAppearance>> stored = new HashMap<>();
        var plan = new BoardDoors.Plan(List.of(HEX, SOUTH), cell -> stored.getOrDefault(cell, Map.of()));
        for (Door door : doors) { plan.set(door, 2, cell -> 3); }
        return plan;
    }

    @Test
    void doorsTouchWhereTheirSidesShareOneCorner() {
        assertTrue(BoardDoors.touch(UPPER, MIDDLE), "Side by side in one hex");
        assertTrue(BoardDoors.touch(MIDDLE, LOWER), "Meeting at a corner of two hexes");
        assertFalse(BoardDoors.touch(UPPER, LOWER), "Apart");
        assertFalse(BoardDoors.touch(new Door(HEX, 3), new Door(SOUTH, 0)), "One hexside from both its hexes");
        assertFalse(BoardDoors.touch(new Door(HEX, 0), new Door(HEX, 3)), "Opposite sides");
    }

    @Test
    void linkedDoorsAreOneOpeningOfOneHeight() {
        var plan = plan(UPPER, MIDDLE, LOWER);
        assertEquals(0, plan.group(UPPER), "A new door is linked to nothing");
        plan.set(LOWER, 1, cell -> 3);
        plan.link(UPPER, LOWER);
        assertFalse(plan.linked(UPPER, LOWER), "Doors that do not touch stay apart");
        plan.link(UPPER, MIDDLE);
        plan.link(MIDDLE, LOWER);
        assertEquals(List.of(1, 1, 1), List.of(plan.height(UPPER), plan.height(MIDDLE), plan.height(LOWER)),
              "Linked, they take the lowest of their heights");
        assertTrue(plan.group(UPPER) > 0 && plan.group(UPPER) == plan.group(LOWER), "One group through the middle door");
        plan.set(MIDDLE, 3, cell -> cell.equals(SOUTH) ? 2 : 3);
        assertEquals(List.of(2, 2, 2), List.of(plan.height(UPPER), plan.height(MIDDLE), plan.height(LOWER)),
              "The opening rises as one, as high as its lowest hex allows");
    }

    @Test
    void partingAnOpeningLeavesEachSideItsOwn() {
        var plan = plan(UPPER, MIDDLE, LOWER);
        plan.link(UPPER, MIDDLE);
        plan.link(MIDDLE, LOWER);
        plan.unlink(MIDDLE, LOWER);
        assertTrue(plan.linked(UPPER, MIDDLE), "The doors above the joint are still one opening");
        assertEquals(0, plan.group(LOWER), "A door left alone is linked to nothing");
        plan.link(MIDDLE, LOWER);
        plan.set(MIDDLE, 0, cell -> 3);
        assertEquals(List.of(0, 0), List.of(plan.group(UPPER), plan.group(LOWER)), "Without the middle door the others no longer touch");
    }

    @Test
    void aPlanReadsBackWhatItStores() {
        var plan = plan(UPPER, MIDDLE, LOWER);
        plan.link(MIDDLE, LOWER);
        Map<Coords, Map<String, HexAppearance>> stored = new HashMap<>();
        for (Coords cell : List.of(HEX, SOUTH)) { stored.put(cell, Map.of("doors", plan.stored(cell))); }
        var read = new BoardDoors.Plan(List.of(HEX, SOUTH), stored::get);
        assertTrue(read.linked(MIDDLE, LOWER));
        assertEquals(0, read.group(UPPER));
        assertEquals(1, read.joints().stream().filter(joint -> read.linked(joint[0], joint[1])).count());
        assertEquals(2, read.joints().size(), "Every pair that touches, linked or not");
    }
}
