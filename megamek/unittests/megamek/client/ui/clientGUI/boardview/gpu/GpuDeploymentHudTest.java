/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Test;

/** The deployment HUD's incoming walk-on edges. */
class GpuDeploymentHudTest {
    @Test
    void incomingEdgesGroupOnlyTheOwnUnitsStillToWalkOn() {
        GpuBattleStatus.Snapshot status = GpuHudFixtures.status(1, GamePhase.MOVEMENT, true, 1,
              0, walkOn(1, GpuBattleStatus.Side.OWN, Board.START_N, 1, null),
              walkOn(2, GpuBattleStatus.Side.OWN, Board.START_N, 2, null),
              walkOn(3, GpuBattleStatus.Side.OWN, Board.START_W, 1, null),
              walkOn(4, GpuBattleStatus.Side.ENEMY, Board.START_S, 1, null),
              walkOn(5, GpuBattleStatus.Side.OWN, Board.START_N, 1, new Coords(3, 0)));

        List<GpuIncomingCues.Edge> edges = GpuIncomingCues.edges(status, 16, 17);

        assertEquals(List.of(Board.START_N, Board.START_W), edges.stream().map(GpuIncomingCues.Edge::zone).toList());
        GpuIncomingCues.Edge north = edges.getFirst();
        assertEquals(2, north.count(), "the placed unit no longer waits");
        assertEquals(1, north.round());
        assertEquals(2, north.lastRound());
        assertEquals(new Coords(7, 0), north.hex());
        assertEquals(new Coords(0, 8), edges.get(1).hex());
    }

    private static GpuBattleStatus.UnitStatus walkOn(int id, GpuBattleStatus.Side side, int zone, int round,
          Coords position) {
        return new GpuBattleStatus.UnitStatus(id, side, false, "Unit " + id, "", "", 0, "Medium", "", "", 0, 0, 1, 1,
              0, 0, "", 0, "", 0, "", 0, 0, 0, 0, false, false, false, false, Entity.DMG_NONE, List.of(), "",
              position, 0, null, List.of(), 2, 0, Player.PLAYER_NONE, List.of(), "", GpuBattleStatus.Marks.NONE, -1,
              0, false, zone, round, GpuBattleStatus.Wait.NONE);
    }
}
