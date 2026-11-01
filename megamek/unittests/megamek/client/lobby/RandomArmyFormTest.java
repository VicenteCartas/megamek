/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import megamek.common.loaders.MekSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RandomArmyFormTest {
    private final List<String> rolledFrom = new ArrayList<>();
    private int serial;
    private RandomArmyForm form;

    private List<MekSummary> roll(int count, String table) {
        List<MekSummary> units = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rolledFrom.add(table);
            MekSummary unit = new MekSummary();
            serial++;
            unit.setName("Unit " + serial);
            unit.setBV(100 * serial);
            unit.setTons(5 * serial);
            units.add(unit);
        }
        return units;
    }

    @BeforeEach
    void setUp() {
        form = new RandomArmyForm(List.of("A", "B"), "B", List.of("Me", "Ally"), this::roll);
    }

    @Test
    void startsOnTheInitialTableOrTheFirst() {
        assertEquals("B", form.table());
        assertEquals("A", new RandomArmyForm(List.of("A"), "X", List.of(), this::roll).table());
        RandomArmyForm empty = new RandomArmyForm(List.of(), null, List.of(), this::roll);
        assertNull(empty.table());
        assertFalse(empty.canRoll());
        empty.roll();
        assertTrue(empty.cards().isEmpty());
        assertNull(empty.playerName());
    }

    @Test
    void rollDealsTheCountFromTheTable() {
        form.count(3);
        form.roll();
        assertEquals(3, form.cards().size());
        assertEquals(List.of("B", "B", "B"), rolledFrom);
        assertEquals(600, form.battleValue());
        assertEquals(30, form.tonnage());
    }

    @Test
    void rollKeepsKeptCardsAndRerollsTheRest() {
        form.count(3);
        form.roll();
        MekSummary kept = form.cards().get(1).unit();
        form.keep(1, true);
        form.roll();
        assertEquals(3, form.cards().size());
        assertSame(kept, form.cards().get(0).unit());
        assertTrue(form.cards().get(0).kept());
        assertEquals(5, rolledFrom.size());
    }

    @Test
    void rerollDiscardAndClearWorkOnOneCard() {
        form.count(2);
        form.roll();
        MekSummary first = form.cards().get(0).unit();
        form.reroll(0);
        assertFalse(first == form.cards().get(0).unit());
        form.discard(1);
        assertEquals(1, form.army().size());
        form.discard(5);
        assertEquals(1, form.army().size());
        form.clear();
        assertTrue(form.army().isEmpty());
    }

    @Test
    void countAndPlayerStayInRange() {
        form.count(0);
        assertEquals(1, form.count());
        form.count(100);
        assertEquals(RandomArmyForm.MAX_UNITS, form.count());
        form.player(1);
        assertEquals("Ally", form.playerName());
        form.player(4);
        assertEquals(1, form.player());
        form.table("Z");
        assertEquals("B", form.table());
    }
}
