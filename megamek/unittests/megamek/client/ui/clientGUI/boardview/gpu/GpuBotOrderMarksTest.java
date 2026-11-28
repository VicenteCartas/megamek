/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

import megamek.client.ui.clientGUI.boardview.TacticalMark;
import megamek.client.ui.clientGUI.boardview.spriteHandler.BotRouteSpriteHandler;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.game.BotStatus;
import megamek.common.orders.UnitOrders;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Test;

/** The bot orders overlay's Princess marks: which bots' waypoints and strategic hexes show, and how they group. */
class GpuBotOrderMarksTest {
    private final Board board = new Board(16, 17);
    private final Player viewer = player(1, 1, false);
    private final Player ally = player(2, 1, true);
    private final Player enemy = player(3, 2, true);

    @Test
    void unitsSentAlongTheSameWaypointsShareOneFlagPerWaypointAndATrail() {
        Entity warhammer = unit(21, ally, new Coords(2, 2), "WHM-6R");
        Entity panther = unit(22, ally, new Coords(2, 3), "PNT-9R");
        List<Coords> route = List.of(new Coords(2, 5), new Coords(4, 6));
        BotStatus status = new BotStatus(false, Set.of(), Set.of(), Map.of(21, route, 22, route));

        GpuBotOrderMarks.Marks marks = capture(List.of(panther, warhammer), id -> status);

        assertEquals(List.of(new Coords(2, 5), new Coords(4, 6)),
              marks.flags().stream().map(TacticalMark.Flag::coords).toList());
        assertEquals("WHM-6R +1", marks.flags().get(0).label(), "the lowest id leads; the others are counted");
        assertEquals("2", marks.flags().get(1).progress());
        assertEquals(BotRouteSpriteHandler.routeColor(0), marks.flags().get(0).color());
        assertEquals(List.of(new Coords(2, 3), new Coords(2, 4), new Coords(3, 5)),
              marks.dots().stream().map(TacticalMark.RouteDot::coords).toList(),
              "the trail runs from the lead unit, the flagged hexes and the unit's own left out");
    }

    @Test
    void strategicHexesShowInTheBotsColourAndEnemyBotsShowNothing() {
        Entity enemyUnit = unit(31, enemy, new Coords(8, 8), "AS7-D");
        BotStatus allied = new BotStatus(false, Set.of(), Set.of(new Coords(9, 9), new Coords(40, 40)));
        BotStatus hostile = new BotStatus(false, Set.of(), Set.of(new Coords(1, 1)),
              Map.of(31, List.of(new Coords(8, 10))));

        GpuBotOrderMarks.Marks marks = capture(List.of(enemyUnit), id -> id == ally.getId() ? allied : hostile);

        assertEquals(1, marks.flags().size(), "off the board, and the enemy's, are left out");
        assertEquals(new Coords(9, 9), marks.flags().get(0).coords());
        assertEquals(ally.getColour().getColour(), marks.flags().get(0).color());
        assertEquals(List.of(), marks.dots());
    }

    @Test
    void noViewerOrNoReportsMarkNothing() {
        assertSame(GpuBotOrderMarks.Marks.EMPTY, GpuBotOrderMarks.capture(List.of(), List.of(viewer, ally), null, board,
              id -> new BotStatus(false, Set.of(), Set.of(new Coords(1, 1)))));
        assertSame(GpuBotOrderMarks.Marks.EMPTY, capture(List.of(), id -> BotStatus.NONE));
    }

    private GpuBotOrderMarks.Marks capture(List<Entity> units, IntFunction<BotStatus> status) {
        return GpuBotOrderMarks.capture(units, List.of(viewer, ally, enemy), viewer, board, status);
    }

    private static Player player(int id, int team, boolean bot) {
        Player player = new Player(id, "Player " + id);
        player.setTeam(team);
        player.setBot(bot);
        return player;
    }

    private static Entity unit(int id, Player owner, Coords position, String shortName) {
        Entity unit = mock(Entity.class);
        when(unit.getId()).thenReturn(id);
        when(unit.getOwner()).thenReturn(owner);
        when(unit.getOwnerId()).thenReturn(owner.getId());
        when(unit.getBoardId()).thenReturn(0);
        when(unit.getPosition()).thenReturn(position);
        when(unit.isDeployed()).thenReturn(true);
        when(unit.getShortName()).thenReturn(shortName);
        when(unit.getModel()).thenReturn(shortName);
        when(unit.getUnitOrders()).thenReturn(UnitOrders.NONE);
        return unit;
    }
}
