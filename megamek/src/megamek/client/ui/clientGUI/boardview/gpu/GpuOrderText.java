/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;

import megamek.client.ui.Messages;
import megamek.common.board.Coords;
import megamek.common.game.BotStatus;
import megamek.common.orders.EdgeOrder;
import megamek.common.orders.FormationOrder;
import megamek.common.orders.LanceRole;
import megamek.common.orders.UnitOrders;

/**
 * The one line the HUD shows for an allied bot unit's or lance's orders. A Princess unit has the waypoints its player
 * gave it; a CASPAR unit has its {@link UnitOrders}: a fight off its route, a hold, a route, an edge order, then its
 * formation and lance role. A bot told to hold all its units says so over either.
 */
final class GpuOrderText {
    /** How many waypoints a Princess unit's line names before it trails off. */
    static final int SHOWN_WAYPOINTS = 3;

    private GpuOrderText() {
    }

    /** A Princess unit: the bot-wide hold, else the unit's waypoints in order, else no waypoints. */
    static String princess(BotStatus status, int unitId) {
        if (status.holdingPosition()) {
            return text("GpuBoard.hud.orders.holding");
        }
        List<Coords> waypoints = status.waypointsOf(unitId);
        if (waypoints.isEmpty()) {
            return text("GpuBoard.hud.orders.princess.none");
        }
        return text("GpuBoard.hud.orders.waypoints", hexes(waypoints));
    }

    /**
     * A CASPAR unit in {@code round}: what it does now, then its formation and its lance role ({@code null} for none).
     */
    static String caspar(BotStatus status, UnitOrders orders, LanceRole role, int round) {
        if (status.holdingPosition()) {
            return text("GpuBoard.hud.orders.holding");
        }
        List<String> parts = new ArrayList<>();
        String now = now(orders, round);
        if (now != null) {
            parts.add(now);
        }
        orders.getFormation().map(FormationOrder::getShape)
              .ifPresent(shape -> parts.add(Messages.getString("BotCommandPanel.Formations.shape." + shape.name())));
        if (role != null) {
            parts.add(Messages.getString("BotCommandPanel.Role." + role.getKind().name()));
        }
        return parts.isEmpty() ? text("GpuBoard.hud.orders.none") : String.join(" \u00b7 ", parts);
    }

    /** A lance whose units do not share their orders. */
    static String mixed() {
        return text("GpuBoard.hud.orders.mixed");
    }

    private static String now(UnitOrders orders, int round) {
        if (orders.getFightState().isPresent()) {
            return text("GpuBoard.hud.orders.fight." + orders.getFightState().get().name());
        }
        if (orders.isPaused() || orders.isStoppedInRound(round)) {
            return text("GpuBoard.hud.orders.stopped");
        }
        if (orders.isHoldingAtWaypoint(round)) {
            return text("GpuBoard.hud.orders.holdAt", orders.getRoute().get(0).getBoardNum());
        }
        if (orders.hasRoute()) {
            List<Coords> route = orders.getRoute();
            return text("GpuBoard.hud.orders.route", route.get(route.size() - 1).getBoardNum(), route.size());
        }
        if (orders.getEdgeOrder() != EdgeOrder.NONE) {
            return text("GpuBoard.hud.orders.edge." + orders.getEdgeOrder().name(),
                  Messages.getString("BotCommandPanel.Role.edge." + orders.getEdge().name()));
        }
        return null;
    }

    private static String hexes(List<Coords> waypoints) {
        List<String> names = new ArrayList<>();
        for (Coords hex : waypoints.subList(0, Math.min(SHOWN_WAYPOINTS, waypoints.size()))) {
            names.add(hex.getBoardNum());
        }
        if (waypoints.size() > SHOWN_WAYPOINTS) {
            names.add("\u2026");
        }
        return String.join(" \u2192 ", names);
    }

    private static String text(String key, Object... args) {
        return Messages.getString(key, args);
    }
}
