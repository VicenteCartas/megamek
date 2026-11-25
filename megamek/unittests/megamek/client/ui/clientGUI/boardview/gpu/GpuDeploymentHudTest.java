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

    /** The rail names a unit by its model only when that is a designation, else by its chassis' first word. */
    @Test
    void theRailNamesAUnitByADesignationElseByItsChassis() {
        assertEquals(List.of("STU-K10", "PXH-1", "SAVANNA", "BASILIS", "BASILIS", "TIMBER", "MANTICO"), List.of(
              GpuForcesPanel.code(named("Stuka", "STU-K10")), GpuForcesPanel.code(named("Phoenix Hawk", "PXH-1")),
              GpuForcesPanel.code(named("Savannah Master Hovercraft", "(SL)")),
              GpuForcesPanel.code(named("Basilisk ProtoMek", "B")), GpuForcesPanel.code(named("Basilisk", "3")),
              GpuForcesPanel.code(named("Timber Wolf (Mad Cat)", "Prime")),
              GpuForcesPanel.code(named("Manticore Heavy Tank", ""))));
    }

    private static GpuBattleStatus.UnitStatus named(String chassis, String model) {
        return new GpuBattleStatus.UnitStatus(1, GpuBattleStatus.Side.OWN, false, chassis, chassis, model, 0, "Medium",
              "", "", 0, 0, 1, 1, 0, 0, "", 0, "", 0, "", 0, 0, 0, 0, false, false, false, false, Entity.DMG_NONE,
              List.of(), "", null, 0, null, List.of(), 2, 0, Player.PLAYER_NONE, List.of(), "",
              GpuBattleStatus.Marks.NONE, -1, 0, false, Board.START_NONE, 1, GpuBattleStatus.Wait.NONE);
    }

    private static GpuBattleStatus.UnitStatus walkOn(int id, GpuBattleStatus.Side side, int zone, int round,
          Coords position) {
        return new GpuBattleStatus.UnitStatus(id, side, false, "Unit " + id, "", "", 0, "Medium", "", "", 0, 0, 1, 1,
              0, 0, "", 0, "", 0, "", 0, 0, 0, 0, false, false, false, false, Entity.DMG_NONE, List.of(), "",
              position, 0, null, List.of(), 2, 0, Player.PLAYER_NONE, List.of(), "", GpuBattleStatus.Marks.NONE, -1,
              0, false, zone, round, GpuBattleStatus.Wait.NONE);
    }
}
