/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.board.Coords;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BoardGeometryTest {
    @BeforeAll
    static void loadMathNatives() {
        GdxNativesLoader.load();
    }


    @Test
    void neighborsShareExactlyTheSameEdgeAtEveryScale() {
        BoardGeometry.Tuning original = BoardGeometry.tuning();
        try {
            for (float scale : new float[] { 0.5f, 1, 3 }) {
                BoardGeometry.tune(new BoardGeometry.Tuning(scale, 0.6f, 0.87f, 18, 0.8f));
                for (int column = 0; column < 2; column++) {
                    Coords here = new Coords(column, 1);
                    for (int edge = 0; edge < 6; edge++) {
                        Coords neighbor = here.translated(BoardGeometry.edgeDirection(edge));
                        Vector3 a = BoardGeometry.corner(here, 0, edge);
                        Vector3 b = BoardGeometry.corner(neighbor, 0, edge + 4);
                        assertEquals(0, a.dst(b), 0.001f);
                    }
                }
            }
        } finally {
            BoardGeometry.tune(original);
        }
    }

    @Test
    void unitScaleIsIndependentOfHexScaleAndInvalidDimensionsAreRejected() {
        BoardGeometry.Tuning original = BoardGeometry.tuning();
        try {
            BoardGeometry.tune(new BoardGeometry.Tuning(2, 0.75f, 1.2f, 20, 1));
            assertEquals(40, BoardGeometry.LEVEL);
            assertEquals(0.75f, BoardGeometry.UNIT_SCALE);
            assertEquals(1.2f, BoardGeometry.UNIT_HEIGHT_SCALE);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                  () -> BoardGeometry.tune(new BoardGeometry.Tuning(Float.NaN, 1, 1, 18, 1)));
        } finally {
            BoardGeometry.tune(original);
        }
    }
}
