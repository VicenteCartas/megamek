/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.charset.StandardCharsets;
import java.util.List;

import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The reported four triangular dome clusters and their crossing corridors, through the native board renderer. */
@Tag("on-demand")
class GpuDomeConnectionsSmokeTest {
    @Test
    void capturesTriangularDomeConnections() throws Exception {
        try (var input = getClass().getResourceAsStream("dome-triangle-connections.board2")) {
            var board = BoardFile.readNative(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            GpuMultiHexBuildingSmokeTest.capture(board, List.of(
                  new GpuMultiHexBuildingSmokeTest.View("review-top", new Coords(10, 9), .30f, false, 0),
                  new GpuMultiHexBuildingSmokeTest.View("review-oblique", new Coords(10, 9), .30f, true, -20),
                  new GpuMultiHexBuildingSmokeTest.View("review-dock", new Coords(11, 8), .11f, true, -20),
                  new GpuMultiHexBuildingSmokeTest.View("review-reverse", new Coords(10, 9), .22f, true, 160),
                  new GpuMultiHexBuildingSmokeTest.View("review-junction", new Coords(10, 9), .12f, false, 0)),
                  "dome-connections");
        }
    }
}
