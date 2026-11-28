/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

import jakarta.annotation.Nullable;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.TacticalMark;
import megamek.client.ui.clientGUI.boardview.TacticalStyle;
import megamek.client.ui.clientGUI.boardview.spriteHandler.BotRouteSpriteHandler;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.game.BotStatus;
import megamek.common.orders.RouteGroups;
import megamek.common.units.Entity;

/**
 * EDT: the orders allied Princess bots report ({@link BotStatus}) as board marks, for the bot orders overlay. Units
 * sent along the same waypoints share a flag per waypoint and a trail of dots, in the banner colours after the
 * CASPAR routes {@link BotRouteSpriteHandler} draws; a bot's strategic hexes carry a flag in its own colour. Princess
 * reports arrive without a game event, so the marks are read with each capture rather than kept as sprites.
 */
final class GpuBotOrderMarks {
    /** The marks of one board: the trails' dots and the flags. */
    record Marks(List<TacticalMark.RouteDot> dots, List<TacticalMark.Flag> flags) {
        static final Marks EMPTY = new Marks(List.of(), List.of());

        Marks {
            dots = List.copyOf(dots);
            flags = List.copyOf(flags);
        }
    }

    private GpuBotOrderMarks() { }

    /**
     * @param units   every unit in the game
     * @param players every player in the game
     * @param viewer  the player at this client, or null for none
     * @param board   the board shown
     * @param status  each bot's last report, by player id
     *
     * @return the allied Princess bots' waypoints and strategic hexes on {@code board}
     */
    static Marks capture(List<Entity> units, List<Player> players, @Nullable Player viewer, Board board,
          IntFunction<BotStatus> status) {
        if (viewer == null) {
            return Marks.EMPTY;
        }
        List<Entity> sorted = new ArrayList<>(units);
        sorted.sort(Comparator.comparingInt(Entity::getId));
        Map<Integer, Entity> byId = new LinkedHashMap<>();
        sorted.forEach(unit -> byId.put(unit.getId(), unit));
        int colorIndex = RouteGroups.visibleTo(sorted, viewer).size();
        List<TacticalMark.RouteDot> dots = new ArrayList<>();
        List<TacticalMark.Flag> flags = new ArrayList<>();
        List<Player> bots = players.stream()
              .filter(player -> player.isBot() && player.getId() != viewer.getId() && !viewer.isEnemyOf(player))
              .sorted(Comparator.comparingInt(Player::getId)).toList();
        for (Player bot : bots) {
            BotStatus report = status.apply(bot.getId());
            Map<List<Coords>, List<Entity>> routes = new LinkedHashMap<>();
            for (Entity unit : sorted) {
                List<Coords> route = report.waypointsOf(unit.getId());
                if (!route.isEmpty() && unit.getOwnerId() == bot.getId() && unit.getBoardId() == board.getBoardId()) {
                    routes.computeIfAbsent(route, key -> new ArrayList<>()).add(unit);
                }
            }
            for (Map.Entry<List<Coords>, List<Entity>> route : routes.entrySet()) {
                route(route.getKey(), route.getValue(), BotRouteSpriteHandler.routeColor(colorIndex++), board, dots,
                      flags);
            }
            String strategic = Messages.getString("GpuBoard.hud.botOrders.strategic");
            report.strategicHexes().stream().filter(board::contains)
                  .sorted(Comparator.comparingInt(Coords::getX).thenComparingInt(Coords::getY))
                  .forEach(hex -> flags.add(new TacticalMark.Flag(hex, bot.getColour().getColour(), strategic, null,
                        TacticalMark.Flag.NO_FACING)));
        }
        return dots.isEmpty() && flags.isEmpty() ? Marks.EMPTY : new Marks(dots, flags);
    }

    /** One shared route: a flag on each waypoint, and dots from the lead unit through the waypoints. */
    private static void route(List<Coords> route, List<Entity> units, Color color, Board board,
          List<TacticalMark.RouteDot> dots, List<TacticalMark.Flag> flags) {
        Entity guide = units.get(0);
        String label = units.size() == 1 ? guide.getShortName() : model(guide) + " +" + (units.size() - 1);
        List<Coords> points = new ArrayList<>();
        if (guide.getPosition() != null && guide.isDeployed()) {
            points.add(guide.getPosition());
        }
        points.addAll(route);
        Set<Coords> marked = new HashSet<>(points);
        for (int index = 1; index < points.size(); index++) {
            for (Coords hex : BotRouteSpriteHandler.stepsBetween(points.get(index - 1), points.get(index))) {
                if (board.contains(hex) && marked.add(hex)) {
                    dots.add(new TacticalMark.RouteDot(hex, color, TacticalStyle.ROUTE_TRAIL_DIAMETER));
                }
            }
        }
        for (int step = 0; step < route.size(); step++) {
            if (board.contains(route.get(step))) {
                flags.add(new TacticalMark.Flag(route.get(step), color, label, String.valueOf(step + 1),
                      TacticalMark.Flag.NO_FACING));
            }
        }
    }

    private static String model(Entity unit) {
        String model = unit.getModel();
        return model == null || model.isBlank() ? unit.getChassis() : model;
    }
}
