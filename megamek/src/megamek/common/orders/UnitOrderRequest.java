/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */
package megamek.common.orders;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import jakarta.annotation.Nullable;
import megamek.common.OffBoardDirection;
import megamek.common.board.Coords;

/**
 * One order for one bot unit, as the {@code /unitOrder} chat command gives it: the action and what it needs. A client
 * sends a list of them in one {@link megamek.common.net.enums.PacketCommand#UNIT_ORDERS} packet, so the orders for a
 * whole lance arrive together; the server applies each as the chat command does.
 *
 * @param unitId            the unit
 * @param action            the change to its orders
 * @param hexes             the hexes for a route
 * @param waypointOrders    what to do at each of those hexes; missing ones pass through
 * @param edge              the edge for an edge order
 * @param facingWhileMoving the facing while moving, or {@link UnitOrders#FACING_AUTO}
 * @param facingWhenStopped the facing when stopped, or {@link UnitOrders#FACING_AUTO}
 * @param priority          the route priority, or {@code null}
 * @param formation         the unit's place in a formation, or {@code null}
 * @param role              the lance role for {@link UnitOrderAction#SET_ROLE}, or {@code null} for none
 */
public record UnitOrderRequest(int unitId, UnitOrderAction action, List<Coords> hexes,
      List<WaypointOrder> waypointOrders, OffBoardDirection edge, int facingWhileMoving, int facingWhenStopped,
      @Nullable OrderPriority priority, @Nullable FormationOrder formation, @Nullable LanceRole role)
      implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public UnitOrderRequest {
        Objects.requireNonNull(action);
        // plain lists: the network filter does not take the JDK's immutable ones
        hexes = new ArrayList<>(hexes);
        waypointOrders = new ArrayList<>(waypointOrders);
        edge = (edge == null) ? OffBoardDirection.NONE : edge;
    }

    /** An action that needs nothing more, such as {@link UnitOrderAction#CLEAR}. */
    public static UnitOrderRequest of(int unitId, UnitOrderAction action) {
        return new UnitOrderRequest(unitId, action, List.of(), List.of(), OffBoardDirection.NONE,
              UnitOrders.FACING_AUTO, UnitOrders.FACING_AUTO, null, null, null);
    }

    /** A new route, what to do at its waypoints and its priority ({@code null} keeps the unit's). */
    public static UnitOrderRequest route(int unitId, List<Coords> hexes, List<WaypointOrder> waypointOrders,
          @Nullable OrderPriority priority) {
        return new UnitOrderRequest(unitId, UnitOrderAction.ROUTE, hexes, waypointOrders, OffBoardDirection.NONE,
              UnitOrders.FACING_AUTO, UnitOrders.FACING_AUTO, priority, null, null);
    }

    /** {@link UnitOrderAction#MOVE_TO_EDGE} or {@link UnitOrderAction#EXIT_BY_EDGE} by {@code edge}. */
    public static UnitOrderRequest edge(int unitId, UnitOrderAction action, OffBoardDirection edge) {
        return new UnitOrderRequest(unitId, action, List.of(), List.of(), edge, UnitOrders.FACING_AUTO,
              UnitOrders.FACING_AUTO, null, null, null);
    }

    public static UnitOrderRequest priority(int unitId, OrderPriority priority) {
        return new UnitOrderRequest(unitId, UnitOrderAction.PRIORITY, List.of(), List.of(), OffBoardDirection.NONE,
              UnitOrders.FACING_AUTO, UnitOrders.FACING_AUTO, priority, null, null);
    }

    public static UnitOrderRequest formation(int unitId, FormationOrder formation) {
        return new UnitOrderRequest(unitId, UnitOrderAction.FORMATION, List.of(), List.of(), OffBoardDirection.NONE,
              UnitOrders.FACING_AUTO, UnitOrders.FACING_AUTO, null, formation, null);
    }
}
