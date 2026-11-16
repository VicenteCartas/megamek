/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import megamek.client.ui.Messages;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Test;

/** The deployment HUD's force queue grouping. */
class GpuDeploymentHudTest {
    private static GpuBattleStatus.UnitStatus unit(int id, String formation, String weightClass, int weightIndex) {
        return new GpuBattleStatus.UnitStatus(id, GpuBattleStatus.Side.OWN, false, "Unit " + id, "", "", 0,
              weightClass, formation, "", 0, 0, 1, 1, 0, 0, "", 0, "", 0, "", 0, 0, 0, 0, true, true, false, false,
              Entity.DMG_NONE, List.of(), "", new Coords(id, id), 0, null, List.of(), weightIndex, 0,
              Player.PLAYER_NONE, List.of());
    }

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
              0, false, zone, round);
    }

    @Test
    void formationsGroupTheForceAndTheRestIsUnassigned() {
        List<GpuDeploymentQueue.Group> groups = GpuDeploymentQueue.groups(List.of(unit(1, "Alpha", "Heavy", 3),
              unit(2, "", "Light", 1), unit(3, "Bravo", "Medium", 2), unit(4, "Alpha", "Assault", 4)));
        assertEquals(List.of("Alpha", "Bravo", Messages.getString("GpuBoard.hud.deploy.unassigned")),
              groups.stream().map(GpuDeploymentQueue.Group::name).toList());
        assertEquals(List.of(1, 4), groups.getFirst().units().stream().map(GpuBattleStatus.UnitStatus::id).toList());
        assertFalse(groups.getFirst().automatic());
    }

    @Test
    void aSmallForceWithoutFormationsIsOneGroup() {
        List<GpuBattleStatus.UnitStatus> own = new ArrayList<>();
        for (int id = 1; id <= 12; id++) {
            own.add(unit(id, "", "Medium", 2));
        }
        List<GpuDeploymentQueue.Group> groups = GpuDeploymentQueue.groups(own);
        assertEquals(1, groups.size());
        assertEquals("", groups.getFirst().name());
        assertEquals(12, groups.getFirst().units().size());
        assertEquals(List.of(), GpuDeploymentQueue.groups(List.of()));
    }

    @Test
    void aLargeForceWithoutFormationsGroupsByWeightHeaviestFirst() {
        List<GpuBattleStatus.UnitStatus> own = new ArrayList<>();
        for (int id = 1; id <= 13; id++) {
            own.add(id % 2 == 0 ? unit(id, "", "Light", 1) : unit(id, "", "Assault", 4));
        }
        List<GpuDeploymentQueue.Group> groups = GpuDeploymentQueue.groups(own);
        assertEquals(List.of("Assault", "Light"), groups.stream().map(GpuDeploymentQueue.Group::name).toList());
        assertTrue(groups.getFirst().automatic());
        assertEquals(7, groups.getFirst().units().size());
    }
}
