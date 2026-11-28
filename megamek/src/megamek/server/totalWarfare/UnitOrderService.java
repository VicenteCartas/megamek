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
package megamek.server.totalWarfare;

import static megamek.server.Server.SERVER_CONN;

import jakarta.annotation.Nullable;
import megamek.client.ui.Messages;
import megamek.common.Player;
import megamek.common.orders.LanceRole;
import megamek.common.orders.OrderEligibility;
import megamek.common.orders.UnitOrderAction;
import megamek.common.orders.UnitOrderRequest;
import megamek.common.orders.UnitOrders;
import megamek.common.units.Entity;
import megamek.logging.MMLogger;

/**
 * Gives a bot unit an order: the {@code /unitOrder} chat command and the
 * {@link megamek.common.net.enums.PacketCommand#UNIT_ORDERS} packet both come here, so who may order a unit and what
 * an order does are decided in one place. The orders are stored on the unit and go to every client with it.
 */
public final class UnitOrderService {

    private static final MMLogger LOGGER = MMLogger.create(UnitOrderService.class);

    private final TWGameManager gameManager;

    public UnitOrderService(TWGameManager gameManager) {
        this.gameManager = gameManager;
    }

    /**
     * Applies the order when the sender may give it.
     *
     * @param connId  the sender's connection, or {@link megamek.server.Server#SERVER_CONN}
     * @param request the order
     *
     * @return why the order was refused, or {@code null} when it was applied
     *
     * @throws IllegalArgumentException when the order is missing what its action needs, such as a route's hexes
     */
    public @Nullable String apply(int connId, UnitOrderRequest request) {
        UnitOrderAction action = request.action();
        String refusal = refusal(connId, request.unitId(), action);
        if (refusal != null) {
            return refusal;
        }
        Entity entity = gameManager.getGame().getEntity(request.unitId());
        if (action == UnitOrderAction.SET_ROLE) {
            LanceRole role = request.role();
            entity.setLanceRole(role);
            LOGGER.info("[BotOrders] {} (ID {}) given the lance role {}", entity.getDisplayName(), entity.getId(),
                  (role == null) ? LanceRole.NONE_TEXT : role);
        } else {
            UnitOrders newOrders;
            try {
                newOrders = action.apply(entity.getUnitOrders(), request.hexes(), request.waypointOrders(),
                      request.edge(), request.facingWhileMoving(), request.facingWhenStopped(), request.priority(),
                      gameManager.getGame().getCurrentRound(), request.formation());
            } catch (IllegalArgumentException missingArgument) {
                LOGGER.info("[BotOrders] {} for {} refused: {}", action, entity.getDisplayName(),
                      missingArgument.getMessage());
                throw missingArgument;
            }
            entity.setUnitOrders(newOrders);
            LOGGER.info("[BotOrders] {} (ID {}) given {}: now {}", entity.getDisplayName(), entity.getId(), action,
                  newOrders);
        }
        gameManager.entityUpdate(entity.getId());
        return null;
    }

    /**
     * @param connId the sender's connection
     * @param unitId the unit
     * @param action the order, for the log
     *
     * @return why the sender may not give the unit {@code action}, or {@code null} if they may
     */
    public @Nullable String refusal(int connId, int unitId, UnitOrderAction action) {
        Entity entity = gameManager.getGame().getEntity(unitId);
        if (entity == null) {
            LOGGER.info("[BotOrders] {} refused: no unit {}", action, unitId);
            return Messages.getString("Gamemaster.cmd.missingUnit");
        }
        String refusal = refusalReason(connId, entity);
        if (refusal != null) {
            LOGGER.info("[BotOrders] {} for {} refused: {}", action, entity.getDisplayName(), refusal);
        }
        return refusal;
    }

    /**
     * @return why the sender may not give this unit orders, or {@code null} if they may: the unit's CASPAR bot takes
     *       orders from the server, a gamemaster and anyone not its enemy
     */
    private @Nullable String refusalReason(int connId, Entity entity) {
        Player owner = entity.getOwner();
        if ((owner == null) || !owner.isBot()) {
            return Messages.getString("UnitOrder.cmd.notBot", entity.getDisplayName());
        }
        if (!OrderEligibility.takesUnitOrders(gameManager.getGame(), owner)) {
            return Messages.getString("UnitOrder.cmd.notCaspar", entity.getDisplayName());
        }
        if (!OrderEligibility.isOrderableKind(entity)) {
            return Messages.getString("UnitOrder.cmd.notOrderable", entity.getDisplayName());
        }
        if (connId == SERVER_CONN) {
            return null;
        }
        Player sender = gameManager.getGame().getPlayer(connId);
        if ((sender != null) && sender.getGameMaster()) {
            return null;
        }
        if ((sender == null) || sender.isEnemyOf(owner)) {
            return Messages.getString("UnitOrder.cmd.notAllowed", entity.getDisplayName());
        }
        return null;
    }
}
