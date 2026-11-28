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
package megamek.server.commands;

import java.util.List;
import java.util.Optional;

import jakarta.annotation.Nullable;
import megamek.client.ui.Messages;
import megamek.common.OffBoardDirection;
import megamek.common.board.Coords;
import megamek.common.orders.*;
import megamek.common.units.Entity;
import megamek.logging.MMLogger;
import megamek.server.Server;
import megamek.server.commands.arguments.Argument;
import megamek.server.commands.arguments.Arguments;
import megamek.server.commands.arguments.BooleanArgument;
import megamek.server.commands.arguments.EnumArgument;
import megamek.server.commands.arguments.OptionalEnumArgument;
import megamek.server.commands.arguments.OptionalIntegerArgument;
import megamek.server.commands.arguments.OptionalStringArgument;
import megamek.server.commands.arguments.RouteArgument;
import megamek.server.commands.arguments.UnitArgument;
import megamek.server.totalWarfare.TWGameManager;
import megamek.server.totalWarfare.UnitOrderService;

/**
 * Gives a bot-controlled unit standing orders: a route, pause, resume, stop, an edge to move to or exit by, facing, or
 * route priority. The Bot Commands panel and the map's right-click menu send it; a player can also type it.
 *
 * <p>The orders are stored on the unit, so they are saved with the game and every client, the unit's bot included,
 * receives them with the unit. Anyone on the bot's team may give orders, and so may a gamemaster; an opponent may
 * not.</p>
 *
 * <p>Example: {@code /unitOrder 12 ROUTE hexes=1508-1504 priority=IMPERATIVE}</p>
 */
public class UnitOrderCommand extends ClientServerCommand {

    private static final MMLogger LOGGER = MMLogger.create(UnitOrderCommand.class);

    public static final String COMMAND_NAME = "unitOrder";
    public static final String UNIT_ID = "unitID";
    public static final String ACTION = "action";
    public static final String HEXES = "hexes";
    public static final String EDGE = "edge";
    public static final String FACING_WHILE_MOVING = "moving";
    public static final String FACING_WHEN_STOPPED = "stopped";
    public static final String PRIORITY = "priority";
    public static final String SHAPE = "shape";
    public static final String LEADER = "leader";
    public static final String SPACING = "spacing";
    public static final String SLOT = "slot";
    public static final String PACE = "pace";
    public static final String CONTACT = "contact";
    public static final String TOGETHER = "together";
    public static final String ROLE = "role";

    private static final int HIGHEST_FACING = 5;

    private final UnitOrderService service;

    /**
     * @param server      the server that relays the replies
     * @param gameManager the game manager whose units are ordered
     */
    public UnitOrderCommand(Server server, TWGameManager gameManager) {
        super(server, gameManager, COMMAND_NAME, Messages.getString("UnitOrder.cmd.help"),
              Messages.getString("UnitOrder.cmd.longName"));
        service = new UnitOrderService(gameManager);
    }

    @Override
    public List<Argument<?>> defineArguments() {
        return List.of(new UnitArgument(UNIT_ID, Messages.getString("UnitOrder.cmd.unitID")),
              new EnumArgument<>(ACTION, Messages.getString("UnitOrder.cmd.action"), UnitOrderAction.class),
              new RouteArgument(HEXES, Messages.getString("UnitOrder.cmd.hexes")),
              new OptionalEnumArgument<>(EDGE, Messages.getString("UnitOrder.cmd.edge"), OffBoardDirection.class),
              new OptionalIntegerArgument(FACING_WHILE_MOVING, Messages.getString("UnitOrder.cmd.moving"),
                    UnitOrders.FACING_AUTO, HIGHEST_FACING),
              new OptionalIntegerArgument(FACING_WHEN_STOPPED, Messages.getString("UnitOrder.cmd.stopped"),
                    UnitOrders.FACING_AUTO, HIGHEST_FACING),
              new OptionalEnumArgument<>(PRIORITY, Messages.getString("UnitOrder.cmd.priority"),
                    OrderPriority.class),
              new OptionalEnumArgument<>(SHAPE, Messages.getString("UnitOrder.cmd.shape"), FormationShape.class),
              new OptionalIntegerArgument(LEADER, Messages.getString("UnitOrder.cmd.leader")),
              new OptionalIntegerArgument(SPACING, Messages.getString("UnitOrder.cmd.spacing"),
                    FormationOrder.MINIMUM_SPACING, FormationOrder.MAXIMUM_SPACING),
              new OptionalIntegerArgument(SLOT, Messages.getString("UnitOrder.cmd.slot"), 0, Integer.MAX_VALUE),
              new OptionalEnumArgument<>(PACE, Messages.getString("UnitOrder.cmd.pace"), FormationPace.class),
              new OptionalEnumArgument<>(CONTACT, Messages.getString("UnitOrder.cmd.contact"), ContactRule.class),
              new BooleanArgument(TOGETHER, Messages.getString("UnitOrder.cmd.together"), false),
              new OptionalStringArgument(ROLE, Messages.getString("UnitOrder.cmd.role")));
    }

    @Override
    protected void runCommand(int connId, Arguments args) {
        int unitId = args.get(UNIT_ID, UnitArgument.class).getValue();
        UnitOrderAction action = args.getEnum(ACTION, UnitOrderAction.class);
        String refusal = service.refusal(connId, unitId, action);
        if (refusal != null) {
            server.sendServerChat(connId, refusal);
            return;
        }
        Entity entity = gameManager.getGame().getEntity(unitId);
        RouteArgument route = args.get(HEXES, RouteArgument.class);
        UnitOrderRequest request = new UnitOrderRequest(unitId, action, route.getHexes(), route.getWaypointOrders(),
              (OffBoardDirection) args.get(EDGE).getValue(), facingArgument(args, FACING_WHILE_MOVING),
              facingArgument(args, FACING_WHEN_STOPPED), (OrderPriority) args.get(PRIORITY).getValue(),
              formationArgument(args, entity),
              (action == UnitOrderAction.SET_ROLE) ? roleArgument(args, entity) : null);
        // the command base class replies to a missing argument with the reason and the usage
        refusal = service.apply(connId, request);
        if (refusal != null) {
            server.sendServerChat(connId, refusal);
            return;
        }
        server.sendServerChat(connId,
              Messages.getString("UnitOrder.cmd.success", entity.getDisplayName(), action.name()));
    }

    /**
     * The lance role from the {@code role} argument: {@code NONE} (null), a convoy such as {@code CONVOY:NORTH}, or an
     * escort.
     *
     * @throws IllegalArgumentException when no role is given or the text names none
     */
    private static @Nullable LanceRole roleArgument(Arguments args, Entity entity) {
        // the argument holds an Optional: reading it as text gave "Optional[CONVOY:NORTH]", which no role parses
        Optional<String> roleText = args.get(ROLE, OptionalStringArgument.class).getValue();
        if (roleText.isEmpty() || roleText.get().isBlank()) {
            LOGGER.info("[BotOrders] SET_ROLE for {} refused: no role given", entity.getDisplayName());
            throw new IllegalArgumentException(Messages.getString("UnitOrder.cmd.role"));
        }
        try {
            return LanceRole.NONE_TEXT.equalsIgnoreCase(roleText.get().trim()) ? null
                  : LanceRole.parse(roleText.get());
        } catch (IllegalArgumentException notARole) {
            LOGGER.info("[BotOrders] SET_ROLE for {} refused: {}", entity.getDisplayName(), notARole.getMessage());
            throw notARole;
        }
    }

    /**
     * @param role the role, or {@code null} for none
     *
     * @return the named argument for it, e.g. {@code role=CONVOY:NORTH}
     */
    public static String roleArgument(@Nullable LanceRole role) {
        return ROLE + '=' + ((role == null) ? LanceRole.NONE_TEXT : role.toCommandText());
    }

    /**
     * Builds the chat text that gives a unit an order, for the Bot Commands panel, the map menu and the bots
     * themselves.
     *
     * @param unitId         the unit
     * @param action         the order
     * @param namedArguments any of {@code hexes=...}, {@code edge=...}, {@code moving=...}, {@code stopped=...},
     *                       {@code priority=...}
     *
     * @return the command, e.g. {@code /unitOrder 12 ROUTE hexes=1508-1504}
     */
    public static String commandText(int unitId, UnitOrderAction action, String... namedArguments) {
        StringBuilder text = new StringBuilder("/").append(COMMAND_NAME).append(' ').append(unitId).append(' ')
              .append(action.name());
        for (String namedArgument : namedArguments) {
            text.append(' ').append(namedArgument);
        }
        return text.toString();
    }

    /**
     * @param hexes the route's hexes, in order
     *
     * @return the named argument for them, e.g. {@code hexes=1508-1504}
     */
    public static String hexesArgument(List<Coords> hexes) {
        return hexesArgument(hexes, List.of());
    }

    /**
     * @param hexes          the route's hexes, in order
     * @param waypointOrders what the unit does at each hex, in the same order; missing ones pass through
     *
     * @return the named argument for them, e.g. {@code hexes=1709-1706/NE/2-2005-2204/N}
     */
    public static String hexesArgument(List<Coords> hexes, List<WaypointOrder> waypointOrders) {
        StringBuilder text = new StringBuilder(HEXES).append('=');
        for (int index = 0; index < hexes.size(); index++) {
            if (index > 0) {
                text.append('-');
            }
            text.append(hexes.get(index).getBoardNum());
            if (index < waypointOrders.size()) {
                text.append(waypointOrders.get(index).toCommandSuffix());
            }
        }
        return text.toString();
    }

    /**
     * @return the unit's place in a formation from the {@code shape}, {@code leader}, {@code spacing}, {@code slot},
     *       {@code pace} and {@code contact} arguments, or {@code null} when no shape was given
     */
    private static @Nullable FormationOrder formationArgument(Arguments args, Entity entity) {
        FormationShape shape = (FormationShape) args.get(SHAPE).getValue();
        if (shape == null) {
            return null;
        }
        int leaderId = args.get(LEADER, OptionalIntegerArgument.class).getValue().orElse(entity.getId());
        int spacing = args.get(SPACING, OptionalIntegerArgument.class).getValue()
              .orElse(FormationOrder.DEFAULT_SPACING);
        int slot = args.get(SLOT, OptionalIntegerArgument.class).getValue().orElse(0);
        FormationPace pace = (FormationPace) args.get(PACE).getValue();
        ContactRule contactRule = (ContactRule) args.get(CONTACT).getValue();
        boolean keepTogether = args.get(TOGETHER, BooleanArgument.class).getValue();
        return new FormationOrder(shape, leaderId, spacing, slot, (pace == null) ? FormationPace.WALK : pace,
              (contactRule == null) ? ContactRule.TURN_AND_FIRE : contactRule, keepTogether);
    }

    private static int facingArgument(Arguments args, String name) {
        return args.get(name, OptionalIntegerArgument.class).getValue().orElse(UnitOrders.FACING_AUTO);
    }
}
