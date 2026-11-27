/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;

import megamek.common.OffBoardDirection;
import megamek.common.board.Coords;
import megamek.common.game.BotStatus;
import megamek.common.orders.ContactRule;
import megamek.common.orders.EdgeOrder;
import megamek.common.orders.FightState;
import megamek.common.orders.FormationOrder;
import megamek.common.orders.FormationPace;
import megamek.common.orders.FormationShape;
import megamek.common.orders.LanceRole;
import megamek.common.orders.UnitOrders;
import org.junit.jupiter.api.Test;

/** The one-line orders the HUD shows for allied bot units. */
class GpuOrderTextTest {
    private static final BotStatus FREE = BotStatus.NONE;
    private static final BotStatus HOLDING = new BotStatus(true, Set.of(), Set.of());

    @Test
    void aPrincessUnitShowsItsWaypointsInOrderUpToThree() {
        BotStatus status = new BotStatus(false, Set.of(), Set.of(), Map.of(
              7, List.of(new Coords(11, 5), new Coords(9, 9)),
              8, List.of(new Coords(0, 0), new Coords(1, 1), new Coords(2, 2), new Coords(3, 3))));
        assertEquals("Waypoints \u00b7 1206 \u2192 1010", GpuOrderText.princess(status, 7));
        assertEquals("Waypoints \u00b7 0101 \u2192 0202 \u2192 0303 \u2192 \u2026", GpuOrderText.princess(status, 8));
        assertEquals("No waypoints", GpuOrderText.princess(status, 9));
    }

    @Test
    void holdingAllUnitsSpeaksOverAnyUnitsOrders() {
        BotStatus status = new BotStatus(true, Set.of(), Set.of(), Map.of(7, List.of(new Coords(11, 5))));
        assertEquals("Holding position \u00b7 the whole bot", GpuOrderText.princess(status, 7));
        UnitOrders route = UnitOrders.NONE.withRoute(List.of(new Coords(11, 5)));
        assertEquals("Holding position \u00b7 the whole bot", GpuOrderText.caspar(HOLDING, route, null, 1));
    }

    @Test
    void aCasparUnitShowsWhatItDoesNowThenItsFormationAndRole() {
        assertEquals("No orders", GpuOrderText.caspar(FREE, UnitOrders.NONE, null, 1));

        UnitOrders route = UnitOrders.NONE.withRoute(List.of(new Coords(5, 5), new Coords(11, 5)))
              .withFormation(new FormationOrder(FormationShape.WEDGE, 1, 2, 0, FormationPace.WALK,
                    ContactRule.HOLD));
        assertEquals("Route to 1206 \u00b7 2 waypoints \u00b7 Wedge", GpuOrderText.caspar(FREE, route, null, 1));
        assertEquals("Route to 1206 \u00b7 2 waypoints \u00b7 Wedge \u00b7 Convoy",
              GpuOrderText.caspar(FREE, route, LanceRole.convoy(OffBoardDirection.NORTH), 1));

        assertEquals("Fighting off its route",
              GpuOrderText.caspar(FREE, route.withFormation(null).withFightState(FightState.FIGHTING), null, 1));
        assertEquals("Holding where it is", GpuOrderText.caspar(FREE, route.withFormation(null).withPaused(true),
              null, 1));
        assertEquals("Leaving by the North edge", GpuOrderText.caspar(FREE,
              UnitOrders.NONE.withEdgeOrder(EdgeOrder.EXIT_BY, OffBoardDirection.NORTH), null, 1));
    }
}
