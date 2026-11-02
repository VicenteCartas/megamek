/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import megamek.common.units.Entity;
import org.junit.jupiter.api.Test;

class TrainOrderFormTest {

    private static Entity unit(String name) {
        Entity unit = mock(Entity.class);
        when(unit.getShortName()).thenReturn(name);
        return unit;
    }

    @Test
    void moveShiftsATrailerOnlyWithinTheTrain() {
        Entity head = unit("Tractor");
        Entity first = unit("First");
        Entity second = unit("Second");
        Entity third = unit("Third");
        TrainOrderForm form = new TrainOrderForm(head, List.of(first, second, third));

        assertEquals(1, form.move(0, 1));
        assertEquals(List.of(second, first, third), form.trailers());
        assertEquals(0, form.move(0, -1));
        assertEquals(2, form.move(2, 1));
        assertEquals(List.of(second, first, third), form.trailers());
        assertEquals(0, form.move(2, -2));
        assertEquals(List.of(third, second, first), form.trailers());
        assertEquals("Tractor \u2192 Third \u2192 Second \u2192 First", form.line());
    }
}
