/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import megamek.client.ui.Messages;
import megamek.common.Player;
import megamek.common.board.Coords;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Test;

/** The deployment HUD's turn-order strip summary and the force queue's grouping. */
class GpuDeploymentHudTest {
    private static final int YOU = 1;
    private static final int PRINCESS = 2;

    private static GpuBattleStatus.Slot slot(int player) {
        return new GpuBattleStatus.Slot(player, "P" + player, 0, GpuBattleStatus.Side.OWN, Entity.NONE);
    }

    private static List<GpuBattleStatus.Slot> order(int... players) {
        List<GpuBattleStatus.Slot> slots = new ArrayList<>();
        for (int player : players) {
            slots.add(slot(player));
        }
        return slots;
    }

    private static List<Integer> players(GpuTurnOrder.Upcoming upcoming) {
        return upcoming.next().stream().map(run -> run.slot().playerId()).toList();
    }

    @Test
    void theStripNamesTheTurnsAfterTheCurrentOneInOrder() {
        GpuTurnOrder.Upcoming upcoming = GpuTurnOrder.upcoming(order(YOU, PRINCESS, YOU, PRINCESS, YOU, PRINCESS),
              0, 4);
        assertEquals(List.of(PRINCESS, YOU, PRINCESS, YOU), players(upcoming));
        assertEquals(1, upcoming.more());
    }

    @Test
    void consecutiveTurnsOfOnePlayerFoldIntoOneRun() {
        GpuTurnOrder.Upcoming upcoming = GpuTurnOrder.upcoming(order(YOU, PRINCESS, PRINCESS, PRINCESS, YOU), 0, 4);
        assertEquals(List.of(PRINCESS, YOU), players(upcoming));
        assertEquals(3, upcoming.next().getFirst().count());
        assertEquals(0, upcoming.more());
    }

    @Test
    void theLastTurnHasNothingAfterIt() {
        assertEquals(GpuTurnOrder.Upcoming.EMPTY, GpuTurnOrder.upcoming(order(YOU, PRINCESS), 1, 4));
        assertEquals(GpuTurnOrder.Upcoming.EMPTY, GpuTurnOrder.upcoming(order(YOU), -1, 4));
    }

    private static GpuBattleStatus.UnitStatus unit(int id, String formation, String weightClass, int weightIndex) {
        return new GpuBattleStatus.UnitStatus(id, GpuBattleStatus.Side.OWN, false, "Unit " + id, "", "", 0,
              weightClass, formation, "", 0, 0, 1, 1, 0, 0, "", 0, "", 0, "", 0, 0, 0, 0, true, true, false, false,
              Entity.DMG_NONE, List.of(), "", new Coords(id, id), 0, null, List.of(), weightIndex, 0,
              Player.PLAYER_NONE, List.of());
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
