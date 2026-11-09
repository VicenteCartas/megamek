/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;

import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

/** Road outlines are shared between roads alike (BoardRoad#footprint): each caller still gets its own copy. */
class BoardRoadOutlineTest {
    /** A two-lane road north narrowing into a single lane south: a course whose width varies along it. */
    private static BoardRoad narrowing(Coords at) {
        return BoardRoad.layout(at, 1 | 8, BoardRoad.Kind.PAVED, direction -> BoardRoad.Kind.PAVED, 8, false);
    }

    @Test
    void roadsAlikeShareTheirOutlineAndChangingACopyChangesNoOther() {
        BoardRoad first = narrowing(new Coords(3, 3)), second = narrowing(new Coords(9, 7));
        Area outline = first.footprint(0);
        assertTrue(outline.equals(second.footprint(0)), "The same outline at another hex");
        outline.subtract(new Area(new Rectangle2D.Float(-200, -200, 400, 400)));
        assertTrue(outline.isEmpty());
        assertFalse(second.footprint(0).isEmpty(), "Emptying one copy leaves another road's outline whole");
        assertFalse(first.footprint(0).isEmpty(), "And the road's own");
        assertTrue(first.footprint(0).equals(second.footprint(0)));
    }

    @Test
    void anOutlineIsItsOwnRoadsAndMargins() {
        BoardRoad narrowing = narrowing(new Coords(3, 3));
        BoardRoad plain = BoardRoad.layout(new Coords(3, 3), 1 | 8, BoardRoad.Kind.PAVED, direction -> BoardRoad.Kind.PAVED);
        assertFalse(plain.footprint(0).equals(narrowing.footprint(0)), "Two lanes all the way are another outline");
        assertFalse(narrowing.footprint(0).equals(narrowing.footprint(1.5f)), "Another margin is another outline");
        Area wider = narrowing.footprint(1.5f);
        wider.subtract(narrowing.footprint(0));
        assertFalse(wider.isEmpty(), "The wider margin reaches past the road's own outline");
    }
}
