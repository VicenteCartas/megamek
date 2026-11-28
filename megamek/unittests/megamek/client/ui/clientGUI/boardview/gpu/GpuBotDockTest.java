/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.Set;

import megamek.client.bot.AIType;
import megamek.common.game.BotStatus;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Test;

/** The dock's actions for an allied bot's lance or unit: which show, which can be used, and what they send. */
class GpuBotDockTest {
    private static final int BOT = 9;

    @Test
    void aCasparLanceHoldsUntilEveryUnitHoldsThenResumes() {
        GpuBots.Lance lance = new GpuBots.Lance(5, "Fire lance", List.of(1, 2), 1, "");
        GpuBots.BotRow bot = bot(AIType.CASPAR, lance, Map.of(1, order(false, true, 1, true),
              2, order(true, false, 1, false)));
        GpuBotDock.Target target = new GpuBotDock.Target(bot, lance, lance.units(), Entity.NONE);
        assertEquals(List.of(GpuBotDock.HOLD, GpuBotDock.RETREAT), ids(target));

        bot = bot(AIType.CASPAR, lance, Map.of(1, order(false, true, 1, true), 2, order(true, false, 1, true)));
        target = new GpuBotDock.Target(bot, lance, lance.units(), Entity.NONE);
        GpuBotOrders orders = mock(GpuBotOrders.class);
        GpuBotDock.Action resume = GpuBotDock.action(target, orders, GpuBotDock.RESUME);
        assertTrue(resume.enabled());
        resume.run().run();
        verify(orders).resume(BOT, List.of(1, 2));
    }

    @Test
    void aCasparUnitRejoinsOnlyOnceOutOfItsLancesFormation() {
        GpuBots.Lance lance = new GpuBots.Lance(5, "Fire lance", List.of(1, 2), 1, "");
        GpuBots.BotRow follower = bot(AIType.CASPAR, lance, Map.of(1, order(false, true, 1, false),
              2, order(true, false, 1, false)));
        GpuBotDock.Target target = new GpuBotDock.Target(follower, lance, List.of(2), 2);
        assertEquals(List.of(GpuBotDock.HOLD, GpuBotDock.RETREAT, GpuBotDock.REJOIN), ids(target));
        assertFalse(GpuBotDock.action(target, null, GpuBotDock.REJOIN).enabled(), "it still follows");

        GpuBots.BotRow alone = bot(AIType.CASPAR, lance, Map.of(1, order(false, true, 1, false),
              2, order(false, false, 1, false)));
        target = new GpuBotDock.Target(alone, lance, List.of(2), 2);
        GpuBotOrders orders = mock(GpuBotOrders.class);
        GpuBotDock.Action rejoin = GpuBotDock.action(target, orders, GpuBotDock.REJOIN);
        assertTrue(rejoin.enabled());
        rejoin.run().run();
        verify(orders).rejoin(BOT, 2, 1);

        target = new GpuBotDock.Target(alone, lance, List.of(1), 1);
        assertFalse(GpuBotDock.action(target, null, GpuBotDock.REJOIN).enabled(), "the leader has no one to rejoin");
    }

    @Test
    void aPrincessUnitTakesWaypointsAndNoMore() {
        GpuBots.BotRow none = bot(AIType.PRINCESS, null, Map.of(3, new GpuBots.Order(-1, "No waypoints", false,
              false, 0, false, false)));
        GpuBotDock.Target target = new GpuBotDock.Target(none, null, List.of(3), 3);
        assertEquals(List.of(GpuBotDock.REMOVE_LAST, GpuBotDock.CLEAR), ids(target));
        assertFalse(GpuBotDock.action(target, null, GpuBotDock.REMOVE_LAST).enabled());
        assertFalse(GpuBotDock.action(target, null, GpuBotDock.CLEAR).enabled());
        assertTrue(GpuBotDock.more(target, null).isEmpty());

        GpuBots.BotRow two = bot(AIType.PRINCESS, null, Map.of(3, new GpuBots.Order(-1, "Waypoints", false,
              false, 2, false, true)));
        target = new GpuBotDock.Target(two, null, List.of(3), 3);
        GpuBotOrders orders = mock(GpuBotOrders.class);
        GpuBotDock.Action removeLast = GpuBotDock.action(target, orders, GpuBotDock.REMOVE_LAST);
        assertTrue(removeLast.enabled());
        removeLast.run().run();
        verify(orders).removeLastWaypoint(BOT, List.of(3));
    }

    @Test
    void onlyALanceTakesAFormation() {
        GpuBots.Lance lance = new GpuBots.Lance(5, "Fire lance", List.of(1, 2), 1, "");
        GpuBots.BotRow bot = bot(AIType.CASPAR, lance, Map.of(1, order(false, true, 0, false),
              2, order(true, false, 0, false)));
        List<String> lanceItems = GpuBotDock.more(new GpuBotDock.Target(bot, lance, lance.units(), Entity.NONE),
              null).stream().map(BoardScene.Command::id).toList();
        List<String> unitItems = GpuBotDock.more(new GpuBotDock.Target(bot, lance, List.of(2), 2), null).stream()
              .map(BoardScene.Command::id).toList();
        assertTrue(lanceItems.contains("dock.bot.formation.WEDGE"));
        assertTrue(lanceItems.contains("dock.bot.formation.off"));
        assertFalse(unitItems.contains("dock.bot.formation.WEDGE"));
        assertTrue(unitItems.contains("dock.bot.exit.NORTH"));
        assertTrue(unitItems.contains("dock.bot.priority.IMPERATIVE"));
    }

    private static List<String> ids(GpuBotDock.Target target) {
        return GpuBotDock.row(target, null).stream().map(GpuBotDock.Action::id).toList();
    }

    private static GpuBots.Order order(boolean follows, boolean leader, int waypoints, boolean held) {
        return new GpuBots.Order(5, "Route", follows, leader, waypoints, held, waypoints > 0 || held);
    }

    private static GpuBots.BotRow bot(AIType type, GpuBots.Lance lance, Map<Integer, GpuBots.Order> orders) {
        return new GpuBots.BotRow(BOT, "Kurita Regulars", type, 0xFF82E2CE, true, orders.size(), false, null,
              BotStatus.NONE, List.of(), List.of(), Set.of(), false, lance == null ? List.of() : List.of(lance),
              orders);
    }
}
