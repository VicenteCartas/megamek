/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.List;

import megamek.client.ui.Messages;
import megamek.common.orders.UnitOrders;

/**
 * The facings a bot unit keeps, as the lobby's Facing card sets them: the one it holds while moving and the one it
 * deploys in and turns to whenever it stops, each a hex side (0 north, clockwise to 5 northwest) or
 * {@link UnitOrders#FACING_AUTO} for the bot's own choice.
 * <p>
 * Swing thread.
 */
public final class BotFacingForm {
    /** The choices in the card's order: Auto, then the six hex sides. */
    public static final List<Integer> CHOICES = List.of(UnitOrders.FACING_AUTO, 0, 1, 2, 3, 4, 5);

    private final String unitName;
    private int whileMoving;
    private int whenStopped;

    public BotFacingForm(String unitName, int whileMoving, int whenStopped) {
        this.unitName = unitName;
        this.whileMoving = valid(whileMoving);
        this.whenStopped = valid(whenStopped);
    }

    private static int valid(int facing) {
        return CHOICES.contains(facing) ? facing : UnitOrders.FACING_AUTO;
    }

    public String unitName() {
        return unitName;
    }

    public int whileMoving() {
        return whileMoving;
    }

    public void whileMoving(int facing) {
        whileMoving = valid(facing);
    }

    public int whenStopped() {
        return whenStopped;
    }

    public void whenStopped(int facing) {
        whenStopped = valid(facing);
    }

    /** A facing as the card names it. */
    public static String facingName(int facing) {
        return Messages.getString("BotCommandPanel.Orders.facing."
              + ((facing == UnitOrders.FACING_AUTO) ? "auto" : String.valueOf(facing)));
    }
}
