/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import megamek.client.bot.AIType;
import megamek.client.ui.Messages;
import megamek.common.OffBoardDirection;
import megamek.common.orders.FormationShape;
import megamek.common.orders.OrderPriority;
import megamek.common.units.Entity;

/**
 * The dock's orders for an allied bot's selected lance or unit (BOT_ORDERS): what they apply to, the option row's
 * actions and More's commands. Orders go out at once; CASPAR takes them for a lance or a unit, Princess takes
 * waypoints for a unit.
 */
final class GpuBotDock {
    static final String HOLD = "hold";
    static final String RESUME = "resume";
    static final String RETREAT = "retreat";
    static final String REJOIN = "rejoin";
    static final String REMOVE_LAST = "removeLast";
    static final String CLEAR = "clear";

    private static final OffBoardDirection[] EDGES = { OffBoardDirection.NORTH, OffBoardDirection.EAST,
          OffBoardDirection.SOUTH, OffBoardDirection.WEST };

    private GpuBotDock() { }

    /**
     * What the orders apply to: the bot, the lance (the unit's own lance for a unit, which may be null), the units and
     * the single unit, or {@code Entity.NONE} for the whole lance.
     */
    record Target(GpuBots.BotRow bot, GpuBots.Lance lance, List<Integer> units, int unitId) {
        Target {
            units = List.copyOf(units);
        }

        boolean caspar() {
            return bot.type() == AIType.CASPAR;
        }

        boolean wholeLance() {
            return unitId == Entity.NONE;
        }

        List<GpuBots.Order> orders() {
            return units.stream().map(bot.orders()::get).filter(Objects::nonNull).toList();
        }
    }

    /** An option row action: its id, caption and whether it can be used now. */
    record Action(String id, String label, boolean enabled, Runnable run) { }

    /** The selected allied bot lance or the inspected allied bot unit, or null. */
    static Target target(GpuHudState state) {
        if (state.overview) {
            return null;
        }
        GpuBots.Lance lance = state.selectedLance();
        if (lance != null) {
            GpuBots.BotRow bot = state.bots().bot(state.selectedLanceBot());
            return bot == null ? null : new Target(bot, lance, lance.units(), Entity.NONE);
        }
        int unitId = state.inspected;
        GpuBots.BotRow bot = unitId == Entity.NONE ? null : state.bots().alliedOwnerOf(unitId);
        return bot == null ? null : new Target(bot, bot.lanceOf(unitId), List.of(unitId), unitId);
    }

    /**
     * The option row: CASPAR's Hold (Resume once every unit holds), Retreat and, for a unit outside its lance's
     * formation, Rejoin lance; Princess's Remove last and Clear waypoints.
     */
    static List<Action> row(Target target, GpuBotOrders orders) {
        int botId = target.bot().id();
        List<Integer> units = target.units();
        List<GpuBots.Order> current = target.orders();
        List<Action> row = new ArrayList<>();
        if (!target.caspar()) {
            row.add(new Action(REMOVE_LAST, text("removeLast"), current.stream().anyMatch(o -> o.waypoints() > 0),
                  () -> orders.removeLastWaypoint(botId, units)));
            row.add(new Action(CLEAR, text("clearWaypoints"), current.stream().anyMatch(GpuBots.Order::any),
                  () -> orders.clearOrders(botId, units)));
            return row;
        }
        if (allHeld(current)) {
            row.add(new Action(RESUME, text("resume"), true, () -> orders.resume(botId, units)));
        } else {
            row.add(new Action(HOLD, text("hold"), true, () -> orders.holdUnits(botId, units)));
        }
        row.add(new Action(RETREAT, text("retreat"), true, () -> orders.retreatUnits(botId, units)));
        if (!target.wholeLance()) {
            GpuBots.Lance lance = target.lance();
            GpuBots.Order order = target.bot().orders().get(target.unitId());
            boolean rejoins = lance != null && lance.units().size() > 1 && lance.leaderId() >= 0
                  && lance.leaderId() != target.unitId() && order != null && !order.follows();
            row.add(new Action(REJOIN, text("rejoin"), rejoins,
                  () -> orders.rejoin(botId, target.unitId(), lance.leaderId())));
        }
        return row;
    }

    /** The row action with {@code id} as the target has it now, or null. */
    static Action action(Target target, GpuBotOrders orders, String id) {
        return row(target, orders).stream().filter(action -> action.id().equals(id)).findFirst().orElse(null);
    }

    /**
     * CASPAR's More: Resume while only some units hold, Clear orders, Remove last waypoint, an exit edge, a lance's
     * formation and the route's priority. Princess has none.
     */
    static List<BoardScene.Command> more(Target target, GpuBotOrders orders) {
        if (!target.caspar()) {
            return List.of();
        }
        int botId = target.bot().id();
        List<Integer> units = target.units();
        List<GpuBots.Order> current = target.orders();
        List<BoardScene.Command> items = new ArrayList<>();
        boolean someHeld = current.stream().anyMatch(GpuBots.Order::held);
        items.add(item("resume", text("resume"), someHeld && !allHeld(current), () -> orders.resume(botId, units)));
        items.add(item("clear", text("clearOrders"), current.stream().anyMatch(GpuBots.Order::any),
              () -> orders.clearOrders(botId, units)));
        items.add(item("removeLast", text("removeLastWaypoint"), current.stream().anyMatch(o -> o.waypoints() > 0),
              () -> orders.removeLastWaypoint(botId, units)));
        for (OffBoardDirection edge : EDGES) {
            items.add(item("exit." + edge.name(), text("exit." + edge.name()), true,
                  () -> orders.exitBy(botId, units, edge)));
        }
        if (target.wholeLance() && units.size() > 1) {
            for (FormationShape shape : FormationShape.values()) {
                items.add(item("formation." + shape.name(), text("formation",
                      Messages.getString("BotCommandPanel.Formations.shape." + shape.name())), true,
                      () -> orders.formation(botId, units, shape)));
            }
            items.add(item("formation.off", text("formationOff"), true, () -> orders.formation(botId, units, null)));
        }
        for (OrderPriority priority : OrderPriority.values()) {
            items.add(item("priority." + priority.name(), text("priority." + priority.name()), true,
                  () -> orders.routePriority(botId, units, priority)));
        }
        return items;
    }

    /** Move to…: a hex picked on the board becomes the units' new route (a Princess unit's only waypoint). */
    static void moveTo(Target target, GpuBotOrders orders, GpuHudState state) {
        orders.moveTo(target.bot().id(), target.units(), text("pickMove", name(target, state)));
    }

    /** Add waypoint…: hexes picked on the board are added to the end of the units' routes. */
    static void addWaypoints(Target target, GpuBotOrders orders, GpuHudState state) {
        orders.addWaypoints(target.bot().id(), target.units(), text("pickWaypoints", name(target, state)));
    }

    /** The head: "Orders for Fire lance · 4" or "Orders for Archer". */
    static String head(Target target, GpuHudState state) {
        return target.wholeLance() ? text("headLance", target.lance().name(), target.units().size())
              : text("headUnit", name(target, state));
    }

    /** The foot's bold part: the units an order applies to. */
    static String scope(Target target, GpuHudState state) {
        return target.wholeLance() ? text("scopeLance", target.units().size(), target.lance().name())
              : text("scopeUnit", name(target, state));
    }

    /** The foot's note: a lance's units take orders alone; a follower leaves its formation on a new route. */
    static String note(Target target) {
        if (target.wholeLance()) {
            return text("noteLance");
        }
        GpuBots.Order order = target.bot().orders().get(target.unitId());
        return order != null && order.follows() && target.lance() != null
              ? text("noteFollower", target.lance().name()) : "";
    }

    /** The lance's name, or the unit's chassis. */
    static String name(Target target, GpuHudState state) {
        if (target.wholeLance()) {
            return target.lance().name();
        }
        GpuBattleStatus.UnitStatus unit = state.presented(target.unitId());
        return unit == null ? Messages.getString("GpuBoard.hud.bots.unit", target.unitId()) : unit.chassis();
    }

    private static boolean allHeld(List<GpuBots.Order> orders) {
        return !orders.isEmpty() && orders.stream().allMatch(GpuBots.Order::held);
    }

    private static BoardScene.Command item(String id, String label, boolean enabled, Runnable action) {
        return new BoardScene.Command("dock.bot." + id, label, "", enabled, false, List.of(), action);
    }

    private static String text(String key, Object... args) {
        return Messages.getString("GpuBoard.hud.dock.bot." + key, args);
    }
}
