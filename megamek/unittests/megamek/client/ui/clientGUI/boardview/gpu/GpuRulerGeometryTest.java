/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class GpuRulerGeometryTest {
    @Test
    void redBeginsAtTheEntryEdgeOfTheBlockingHexOnAStraightElevatedRay() {
        var ruler = new BoardTactical.Ruler(new Coords(0, 0), new Coords(0, 6), 2, 5,
              new Coords(0, 3), null, 0, 0);
        float split = GpuTactical.rulerBlockedFrom(ruler);
        assertEquals(2.5f / 6, split, .00001f);
        var start = BoardGeometry.center(ruler.start(), ruler.startHeight());
        var end = BoardGeometry.center(ruler.end(), ruler.endHeight());
        assertEquals(3.25f * BoardGeometry.level(), start.lerp(end, split).z, .0001f);
        var clear = new BoardTactical.Ruler(ruler.start(), ruler.end(), 2, 5, null, null, 0, 0);
        assertEquals(1, GpuTactical.rulerBlockedFrom(clear));
        assertEquals(1, GpuTactical.rulerModifierFrom(clear));
        var vertical = new BoardTactical.Ruler(ruler.start(), ruler.start(), 1, 4, ruler.start(), null, 0, 0);
        assertEquals(0, GpuTactical.rulerBlockedFrom(vertical));
    }

    @Test
    void yellowBeginsAtTheModifierEntryAndNeverExtendsPastABlocker() {
        var ruler = new BoardTactical.Ruler(new Coords(0, 0), new Coords(0, 6), 2, 5,
              new Coords(0, 4), new Coords(0, 2), 0, 0);
        assertEquals(1.5f / 6, GpuTactical.rulerModifierFrom(ruler), .00001f);
        assertEquals(3.5f / 6, GpuTactical.rulerBlockedFrom(ruler), .00001f);
        var blockedFirst = new BoardTactical.Ruler(ruler.start(), ruler.end(), 2, 5,
              ruler.modifierAt(), ruler.blockedAt(), 0, 0);
        assertEquals(GpuTactical.rulerBlockedFrom(blockedFirst), GpuTactical.rulerModifierFrom(blockedFirst));
        var modifierOnly = new BoardTactical.Ruler(ruler.start(), ruler.end(), 2, 5, null, ruler.modifierAt(), 0, 0);
        assertEquals(1.5f / 6, GpuTactical.rulerModifierFrom(modifierOnly), .00001f);
        assertEquals(1, GpuTactical.rulerBlockedFrom(modifierOnly));
    }

    @Test
    void liveMeasurementAndClearingSurviveMovementPlayback() {
        var old = new BoardTactical.Ruler(new Coords(0, 0), new Coords(0, 6), 2, 2, new Coords(0, 3), null, 0, 0);
        var changed = new BoardTactical.Ruler(old.start(), old.end(), 5, 5, null, null, 0, 0);
        var settled = new BoardTactical(List.of(), List.of()).withRuler(old);
        assertSame(changed, BoardTactical.EMPTY.withRuler(changed).duringPlayback(settled, true).ruler());
        assertNull(BoardTactical.EMPTY.duringPlayback(settled, true).ruler());
        assertSame(old, settled.ruler());
    }
}
