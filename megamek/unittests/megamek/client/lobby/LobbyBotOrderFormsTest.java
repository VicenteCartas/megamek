/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;

import megamek.common.OffBoardDirection;
import megamek.common.orders.LanceRole;
import megamek.common.orders.LanceRoles;
import megamek.common.orders.UnitOrders;
import org.junit.jupiter.api.Test;

/** The bot order cards' forms: the facings and the lance role, as the lobby's native cards set them. */
class LobbyBotOrderFormsTest {
    private static final LanceRoles.ConvoyChoice CONVOY = new LanceRoles.ConvoyChoice(7, "Trucks", 4, true,
          OffBoardDirection.SOUTH);
    private static final LanceRoles.ConvoyChoice LANCE = new LanceRoles.ConvoyChoice(8, "Recon", 3, false,
          OffBoardDirection.EAST);

    @Test
    void facingKeepsValidFacingsAndFallsBackToAuto() {
        BotFacingForm form = new BotFacingForm("Atlas", 3, 9);
        assertEquals(3, form.whileMoving());
        assertEquals(UnitOrders.FACING_AUTO, form.whenStopped());
        form.whenStopped(5);
        form.whileMoving(-7);
        assertEquals(5, form.whenStopped());
        assertEquals(UnitOrders.FACING_AUTO, form.whileMoving());
        assertEquals(7, BotFacingForm.CHOICES.size());
        for (int facing : BotFacingForm.CHOICES) {
            assertFalse(BotFacingForm.facingName(facing).isBlank());
        }
    }

    @Test
    void noRoleIsCompleteAndSendsNothing() {
        LanceRoleForm form = new LanceRoleForm("Alpha", List.of(), OffBoardDirection.WEST, null);
        assertEquals(LanceRoleForm.Choice.NONE, form.choice());
        assertTrue(form.complete());
        assertNull(form.role());
        assertTrue(form.lanceToMakeConvoy().isEmpty());
    }

    @Test
    void convoyLeavesByItsEdgeOrWaits() {
        LanceRoleForm form = new LanceRoleForm("Alpha", List.of(), OffBoardDirection.WEST, null);
        form.choice(LanceRoleForm.Choice.CONVOY);
        assertEquals(OffBoardDirection.WEST, form.exitEdge());
        form.exitEdge(OffBoardDirection.NONE);
        assertEquals(OffBoardDirection.WEST, form.exitEdge(), "only a board edge is an exit");
        form.exitEdge(OffBoardDirection.NORTH);
        LanceRole role = form.role();
        assertTrue(role.isConvoy());
        assertEquals(OffBoardDirection.NORTH, role.getExitEdge());
        assertFalse(role.isWaitingAtRouteEnd());
        assertTrue(form.edgeApplies());

        form.waits(true);
        assertFalse(form.edgeApplies());
        assertTrue(form.role().isWaitingAtRouteEnd());
        assertFalse(form.convoyHelp().isBlank());
    }

    @Test
    void shownConvoyLoadsItsSettings() {
        LanceRoleForm form = new LanceRoleForm("Alpha", List.of(), OffBoardDirection.WEST,
              LanceRole.convoy(OffBoardDirection.EAST, true));
        assertEquals(LanceRoleForm.Choice.CONVOY, form.choice());
        assertEquals(OffBoardDirection.EAST, form.exitEdge());
        assertTrue(form.waits());
    }

    @Test
    void escortNeedsAPlaceRoundTheConvoy() {
        LanceRoleForm form = new LanceRoleForm("Alpha", List.of(CONVOY, LANCE), OffBoardDirection.WEST, null);
        form.choice(LanceRoleForm.Choice.ESCORT);
        for (LanceRole.Position position : LanceRole.Position.values()) {
            if (form.holds(position)) {
                form.toggle(position);
            }
        }
        assertFalse(form.complete());
        assertNull(form.role());
        assertFalse(form.escortSummary().isBlank());

        form.toggle(LanceRole.Position.LEAD);
        assertTrue(form.complete());
        LanceRole role = form.role();
        assertTrue(role.isEscort());
        assertEquals(7, role.getConvoyForceId());
        assertEquals(EnumSet.of(LanceRole.Position.LEAD), role.getPositions());
        assertTrue(form.lanceToMakeConvoy().isEmpty(), "a convoy needs no making");

        form.surround();
        assertEquals(EnumSet.allOf(LanceRole.Position.class), form.role().getPositions());
    }

    @Test
    void escortingALanceMakesItAConvoy() {
        LanceRoleForm form = new LanceRoleForm("Alpha", List.of(CONVOY, LANCE), OffBoardDirection.WEST, null);
        form.choice(LanceRoleForm.Choice.ESCORT);
        form.convoy(1);
        form.convoy(5);
        assertEquals(1, form.convoy(), "an index past the lances is ignored");
        form.surround();
        assertEquals(8, form.role().getConvoyForceId());
        assertEquals(LANCE, form.lanceToMakeConvoy().orElseThrow());
        assertTrue(form.escortSummary().contains("Recon"));
    }

    @Test
    void shownEscortLoadsItsConvoy() {
        LanceRole shown = LanceRole.defaultEscort(8);
        LanceRoleForm form = new LanceRoleForm("Alpha", List.of(CONVOY, LANCE), OffBoardDirection.WEST, shown);
        assertEquals(LanceRoleForm.Choice.ESCORT, form.choice());
        assertEquals(1, form.convoy());
        for (LanceRole.Position position : LanceRole.Position.values()) {
            assertEquals(shown.getPositions().contains(position), form.holds(position));
        }
    }

    @Test
    void leaveToFightAppliesOnlyWhenBreakingOff() {
        LanceRoleForm form = new LanceRoleForm("Alpha", List.of(CONVOY), OffBoardDirection.WEST, null);
        form.contact(LanceRole.Contact.SCREEN);
        assertFalse(form.leaveApplies());
        assertFalse(form.leaveHint().isBlank());
        form.contact(LanceRole.Contact.BREAK_AND_FIGHT);
        assertTrue(form.leaveApplies());
        assertFalse(form.distanceHint().isBlank());
        assertFalse(form.contactHint().isBlank());
        assertFalse(form.goneHint().isBlank());
    }
}
