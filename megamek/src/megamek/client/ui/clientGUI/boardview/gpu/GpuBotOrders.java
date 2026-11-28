/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import megamek.client.bot.AIType;
import megamek.client.bot.princess.BehaviorChange;
import megamek.client.bot.princess.BehaviorChange.Trait;
import megamek.client.bot.princess.BehaviorSettings;
import megamek.client.bot.princess.BotOrder;
import megamek.client.bot.princess.CardinalEdge;
import megamek.client.bot.princess.CombatPosture;
import megamek.client.bot.princess.PrincessException;
import megamek.client.bot.princess.Tactic;
import megamek.client.lobby.BotSetupForm.Personality;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBots.Control;
import megamek.client.ui.dialogs.BotCommands.HexTargetPicker;
import megamek.client.ui.dialogs.BotCommands.MoveOrderCommands;
import megamek.common.OffBoardDirection;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.equipment.GunEmplacement;
import megamek.common.game.Game;
import megamek.common.orders.FormationOrder;
import megamek.common.orders.FormationShape;
import megamek.common.orders.OrderPriority;
import megamek.common.orders.UnitOrderAction;
import megamek.common.orders.UnitOrderRequest;
import megamek.common.orders.UnitOrders;
import megamek.common.units.BuildingEntity;
import megamek.common.units.Entity;
import megamek.logging.MMLogger;

/**
 * The bot sheet's commands. Each is called on the GL thread, runs on the EDT through {@link GpuBoardSource#command}
 * and goes to the bot as a typed message, never as chat; it carries only the value the player changed. The control
 * then shows as sending ({@link GpuBots#sending}) until the bot reports.
 */
final class GpuBotOrders {
    private static final MMLogger LOGGER = MMLogger.create(GpuBotOrders.class);

    /** Where the messages go: the client, or a test's recorder. */
    interface Sender {
        void behavior(int botId, List<BehaviorChange> changes);

        void order(int botId, BotOrder order);

        /** Orders for CASPAR units, applied by the server in this order. */
        void units(List<UnitOrderRequest> orders);
    }

    private final GpuBoardSource source;
    private final GpuBots bots;
    private final Sender sender;

    GpuBotOrders(GpuBoardSource source, GpuBots bots, Sender sender) {
        this.source = source;
        this.bots = bots;
        this.sender = sender;
    }

    /** The client of {@code source}'s game, or a sender that drops the messages when there is none. */
    static Sender clientOf(GpuBoardSource source) {
        return new Sender() {
            @Override
            public void behavior(int botId, List<BehaviorChange> changes) {
                var gui = source.currentView().getClientgui();
                if (gui != null) {
                    gui.getClient().sendBotBehaviorChange(botId, changes);
                }
            }

            @Override
            public void order(int botId, BotOrder order) {
                var gui = source.currentView().getClientgui();
                if (gui != null) {
                    gui.getClient().sendBotOrder(botId, order);
                }
            }

            @Override
            public void units(List<UnitOrderRequest> orders) {
                var gui = source.currentView().getClientgui();
                if (gui != null) {
                    gui.getClient().sendUnitOrders(orders);
                }
            }
        };
    }

    /** Takes {@code personality}'s behavior values; the posture, targets and retreat stay. */
    void personality(int botId, Personality personality) {
        source.command(() -> {
            BehaviorSettings preset = bots.presets().get(personality.preset());
            if (preset == null) {
                LOGGER.warn("No stored preset {} for the personality {}", personality.preset(), personality);
                return;
            }
            BehaviorSettings copy;
            try {
                copy = preset.getCopy();
            } catch (PrincessException failure) {
                LOGGER.error(failure, "Could not copy the preset {}", personality.preset());
                return;
            }
            Map<Trait, Integer> traits = new EnumMap<>(Trait.class);
            for (Trait trait : Trait.values()) {
                traits.put(trait, trait.of(copy));
            }
            bots.sending(botId, Control.PERSONALITY, asked -> asked.personality(personality, traits));
            send(botId, Control.PERSONALITY, new BehaviorChange.Style(copy));
        });
    }

    void posture(int botId, CombatPosture posture) {
        source.command(() -> {
            bots.sending(botId, Control.POSTURE, asked -> asked.posture(posture));
            send(botId, Control.POSTURE, new BehaviorChange.Posture(posture));
        });
    }

    /** Sets one behavior index, 0 to 10. */
    void trait(int botId, Trait trait, int value) {
        source.command(() -> {
            bots.sending(botId, Control.TRAITS, asked -> asked.trait(trait, value));
            send(botId, Control.TRAITS, new BehaviorChange.Index(trait, value));
        });
    }

    /**
     * Sets the indices of {@code tactic}. No Prisoners has none: it forgives no one, so the bot stops ignoring units
     * and holds a blood feud against every enemy player.
     */
    void tactic(int botId, Tactic tactic) {
        source.command(() -> {
            List<BehaviorChange> changes = new ArrayList<>(tactic.indices());
            if (tactic.dropsMercy()) {
                changes.add(new BehaviorChange.ClearIgnored());
            }
            bots.sending(botId, Control.TACTIC, asked -> tactic.indices()
                  .forEach(index -> asked.trait(index.trait(), index.value())));
            send(botId, Control.TACTIC, changes);
            if (tactic.dropsMercy()) {
                for (Player enemy : enemyPlayers(botId)) {
                    sender.order(botId, new BotOrder.BloodFeud(enemy.getId()));
                }
            }
        });
    }

    void hold(int botId, boolean hold) {
        source.command(() -> {
            bots.sending(botId, Control.HOLD, asked -> asked.hold(hold));
            sender.order(botId, new BotOrder.Hold(hold));
        });
    }

    /** Sends every unit off the board by {@code edge}, or calls the retreat off with {@link CardinalEdge#NONE}. */
    void retreat(int botId, CardinalEdge edge) {
        source.command(() -> {
            bots.sending(botId, Control.RETREAT, asked -> asked.retreat(edge));
            send(botId, Control.RETREAT, new BehaviorChange.Retreat(edge));
        });
    }

    /** Sets a unit's target priority, 1 (first) to 5, or takes it off the list with 0. */
    void priority(int botId, int unitId, int priority) {
        source.command(() -> {
            bots.sending(botId, Control.PRIORITY, asked -> asked.priority(unitId, priority));
            send(botId, Control.PRIORITY, new BehaviorChange.PriorityTarget(unitId, priority));
        });
    }

    /** Adds or removes a strategic hex. */
    void hex(int botId, Coords hex, boolean add) {
        source.command(() -> {
            bots.sending(botId, Control.HEX, asked -> { });
            send(botId, Control.HEX, new BehaviorChange.StrategicHex(hex, add));
        });
    }

    /**
     * Starts a pick of strategic hexes on the board, which the hint line's chip shows as {@code description} with
     * Done and Cancel: Done adds the clicked hexes, Cancel adds none. Without a client there is no board to pick on.
     */
    void pickHexes(int botId, String description) {
        source.command(() -> {
            BoardClientState view = source.currentView();
            ClientGUI gui = view.getClientgui();
            if (gui == null) {
                return;
            }
            new HexTargetPicker(gui, view, description, false, 0, picked -> {
                List<BehaviorChange> changes = new ArrayList<>();
                for (String number : picked.split("-")) {
                    changes.add(new BehaviorChange.StrategicHex(Coords.parseHexNumber(number), true));
                }
                bots.sending(botId, Control.HEX, asked -> { });
                send(botId, Control.HEX, changes);
            }).start();
        });
    }

    /** Adds units to the ignored units, or takes them off. */
    void ignore(int botId, Set<Integer> unitIds, boolean add) {
        source.command(() -> {
            bots.sending(botId, Control.IGNORE, asked -> { });
            send(botId, Control.IGNORE, new BehaviorChange.Ignore(unitIds, add));
        });
    }

    /** Ignores every enemy gun emplacement and building, as the Ignore Turrets command does. */
    void ignoreTurrets(int botId) {
        source.command(() -> {
            Game game = source.currentView().game;
            Player bot = game.getPlayer(botId);
            Set<Integer> turrets = new HashSet<>();
            for (Entity entity : game.getEntitiesVector()) {
                if ((entity instanceof GunEmplacement || entity instanceof BuildingEntity)
                      && (bot == null || bot.isEnemyOf(entity.getOwner()))) {
                    turrets.add(entity.getId());
                }
            }
            if (!turrets.isEmpty()) {
                bots.sending(botId, Control.IGNORE, asked -> { });
                send(botId, Control.IGNORE, new BehaviorChange.Ignore(turrets, true));
            }
        });
    }

    void clearIgnored(int botId) {
        source.command(() -> {
            bots.sending(botId, Control.IGNORE, asked -> { });
            send(botId, Control.IGNORE, new BehaviorChange.ClearIgnored());
        });
    }

    /** The bot holds a grudge against {@code playerId}, as if that player had broken the rules of honor. */
    void bloodFeud(int botId, int playerId) {
        source.command(() -> {
            bots.sending(botId, Control.BLOOD_FEUD, asked -> { });
            sender.order(botId, new BotOrder.BloodFeud(playerId));
        });
    }

    /** Has the bot's TAG-carrying units designate {@code unitId}. */
    void tag(int botId, int unitId) {
        source.command(() -> {
            bots.sending(botId, Control.TAG, asked -> { });
            sender.order(botId, new BotOrder.TagTarget(unitId));
        });
    }

    void clearTags(int botId) {
        source.command(() -> {
            bots.sending(botId, Control.TAG, asked -> { });
            sender.order(botId, new BotOrder.ClearTagTargets());
        });
    }

    // ------------------------------------------------------------------ unit orders (the dock)

    /**
     * Starts a pick of one hex on the board, shown as {@code description}, and sends the units there: a CASPAR unit
     * on a new route, a Princess unit with that hex as its only waypoint.
     */
    void moveTo(int botId, List<Integer> unitIds, String description) {
        pick(description, true, hexes -> routeOrders(botId, unitIds, hexes, false));
    }

    /** Starts a pick of hexes on the board, shown as {@code description}, and adds them to the units' routes. */
    void addWaypoints(int botId, List<Integer> unitIds, String description) {
        pick(description, false, hexes -> routeOrders(botId, unitIds, hexes, true));
    }

    /** CASPAR units drop their route and hold where they are, keeping their formation, until Resume or a new route. */
    void holdUnits(int botId, List<Integer> unitIds) {
        units(botId, unitIds, UnitOrderAction.HOLD_POSITION);
    }

    void resume(int botId, List<Integer> unitIds) {
        units(botId, unitIds, UnitOrderAction.RESUME);
    }

    /** Clears a CASPAR unit's orders, or a Princess unit's waypoints. */
    void clearOrders(int botId, List<Integer> unitIds) {
        source.command(() -> {
            if (caspar(botId)) {
                sender.units(each(unitIds, UnitOrderAction.CLEAR));
            } else {
                unitIds.forEach(unitId -> sender.order(botId, new BotOrder.ClearWaypoints(unitId)));
            }
        });
    }

    void removeLastWaypoint(int botId, List<Integer> unitIds) {
        source.command(() -> {
            if (caspar(botId)) {
                sender.units(each(unitIds, UnitOrderAction.REMOVE_LAST));
            } else {
                unitIds.forEach(unitId -> sender.order(botId, new BotOrder.RemoveLastWaypoint(unitId)));
            }
        });
    }

    /**
     * CASPAR units leave the board by the bot's retreat edge, or by the edge nearest the first unit when the bot has
     * none of the four.
     */
    void retreatUnits(int botId, List<Integer> unitIds) {
        source.command(() -> {
            Game game = source.currentView().game;
            BehaviorSettings settings = settings(game, botId);
            OffBoardDirection edge = retreatEdge(game, settings == null ? CardinalEdge.NONE
                  : settings.getRetreatEdge(), unitIds);
            if (edge != OffBoardDirection.NONE) {
                sender.units(unitIds.stream()
                      .map(unitId -> UnitOrderRequest.edge(unitId, UnitOrderAction.EXIT_BY_EDGE, edge)).toList());
            }
        });
    }

    void exitBy(int botId, List<Integer> unitIds, OffBoardDirection edge) {
        source.command(() -> sender.units(unitIds.stream()
              .map(unitId -> UnitOrderRequest.edge(unitId, UnitOrderAction.EXIT_BY_EDGE, edge)).toList()));
    }

    /** Puts a CASPAR lance in {@code shape}, its first unit leading, or takes it out of formation with null. */
    void formation(int botId, List<Integer> unitIds, FormationShape shape) {
        source.command(() -> sender.units(formationOrders(source.currentView().game, unitIds, shape)));
    }

    void routePriority(int botId, List<Integer> unitIds, OrderPriority priority) {
        source.command(() -> sender.units(unitIds.stream()
              .map(unitId -> UnitOrderRequest.priority(unitId, priority)).toList()));
    }

    /** The CASPAR unit joins its lance's formation again, after the last unit, on the leader's route. */
    void rejoin(int botId, int unitId, int leaderId) {
        source.command(() -> sender.units(rejoinOrders(source.currentView().game, unitId, leaderId)));
    }

    private void units(int botId, List<Integer> unitIds, UnitOrderAction action) {
        source.command(() -> sender.units(each(unitIds, action)));
    }

    /** Runs a board pick on the EDT; without a client there is no board to pick on. */
    private void pick(String description, boolean single, Consumer<List<Coords>> picked) {
        source.command(() -> {
            BoardClientState view = source.currentView();
            ClientGUI gui = view.getClientgui();
            if (gui == null) {
                return;
            }
            new HexTargetPicker(gui, view, description, single, 0, numbers -> picked.accept(hexes(numbers)))
                  .start();
        });
    }

    /** EDT: the picked hexes as a new route ({@code add} false) or added to it. */
    void routeOrders(int botId, List<Integer> unitIds, List<Coords> hexes, boolean add) {
        if (hexes.isEmpty()) {
            return;
        }
        if (caspar(botId)) {
            sender.units(routeOrders(source.currentView().game, unitIds, hexes, add));
        } else {
            for (int unitId : unitIds) {
                sender.order(botId, add ? new BotOrder.AddWaypoints(unitId, hexes)
                      : new BotOrder.SetWaypoints(unitId, hexes));
            }
        }
    }

    /**
     * A new route, or hexes added to it, for each unit. A unit that follows a leader outside {@code unitIds} leaves
     * that formation first, so it goes where it was sent rather than back to its leader; a lance keeps its own.
     */
    static List<UnitOrderRequest> routeOrders(Game game, List<Integer> unitIds, List<Coords> hexes, boolean add) {
        List<UnitOrderRequest> orders = new ArrayList<>();
        for (int unitId : unitIds) {
            Entity unit = game.getEntity(unitId);
            boolean followsOther = unit != null && unit.getUnitOrders().getFormation()
                  .map(formation -> !unitIds.contains(formation.getLeaderId())).orElse(false);
            if (followsOther && !add) {
                orders.add(UnitOrderRequest.of(unitId, UnitOrderAction.FORMATION_OFF));
            }
            orders.add(add ? new UnitOrderRequest(unitId, UnitOrderAction.ADD, hexes, List.of(),
                  OffBoardDirection.NONE, UnitOrders.FACING_AUTO, UnitOrders.FACING_AUTO, null, null, null)
                  : UnitOrderRequest.route(unitId, hexes, List.of(), null));
        }
        return orders;
    }

    /** A lance's formation as the lobby's Bot formation menu sets it (MoveOrderCommands), or Formation off. */
    static List<UnitOrderRequest> formationOrders(Game game, List<Integer> unitIds, FormationShape shape) {
        if (shape == null) {
            return each(unitIds, UnitOrderAction.FORMATION_OFF);
        }
        List<Entity> units = unitIds.stream().map(game::getEntity).filter(Objects::nonNull).toList();
        if (units.size() < 2) {
            return List.of();
        }
        List<FormationOrder> formations = MoveOrderCommands.lobbyFormation(units, shape);
        List<UnitOrderRequest> orders = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) {
            orders.add(UnitOrderRequest.formation(units.get(i).getId(), formations.get(i)));
        }
        return orders;
    }

    /** The orders that put the unit back in the formation of {@code leaderId}, as MegaMek's Join formation does. */
    static List<UnitOrderRequest> rejoinOrders(Game game, int unitId, int leaderId) {
        Entity leader = game.getEntity(leaderId);
        if (leader == null) {
            return List.of();
        }
        int highestSlot = 0;
        for (Entity member : game.getEntitiesVector()) {
            Optional<FormationOrder> formation = member.getUnitOrders().getFormation();
            if (member.getId() != unitId && formation.isPresent() && formation.get().sharesLeader(leaderId)) {
                highestSlot = Math.max(highestSlot, formation.get().getSlot());
            }
        }
        return MoveOrderCommands.joinOrders(unitId, leader.getUnitOrders(), leaderId, highestSlot);
    }

    /**
     * The edge to leave by: the bot's retreat edge when it is one of the four, else the one nearest the first of the
     * units on the board, or {@link OffBoardDirection#NONE} when none is.
     */
    static OffBoardDirection retreatEdge(Game game, CardinalEdge retreat, List<Integer> unitIds) {
        switch (retreat) {
            case NORTH -> {
                return OffBoardDirection.NORTH;
            }
            case SOUTH -> {
                return OffBoardDirection.SOUTH;
            }
            case EAST -> {
                return OffBoardDirection.EAST;
            }
            case WEST -> {
                return OffBoardDirection.WEST;
            }
            default -> { }
        }
        for (int unitId : unitIds) {
            Entity unit = game.getEntity(unitId);
            if (unit == null || unit.getPosition() == null) {
                continue;
            }
            Board board = game.getBoard(unit.getBoardId());
            Coords at = unit.getPosition();
            Map<OffBoardDirection, Integer> distances = new EnumMap<>(OffBoardDirection.class);
            distances.put(OffBoardDirection.NORTH, at.getY());
            distances.put(OffBoardDirection.SOUTH, board.getHeight() - 1 - at.getY());
            distances.put(OffBoardDirection.WEST, at.getX());
            distances.put(OffBoardDirection.EAST, board.getWidth() - 1 - at.getX());
            return distances.entrySet().stream().min(Map.Entry.comparingByValue()).orElseThrow().getKey();
        }
        return OffBoardDirection.NONE;
    }

    private static List<UnitOrderRequest> each(List<Integer> unitIds, UnitOrderAction action) {
        return unitIds.stream().map(unitId -> UnitOrderRequest.of(unitId, action)).toList();
    }

    private static List<Coords> hexes(String dashSeparated) {
        List<Coords> hexes = new ArrayList<>();
        for (String number : dashSeparated.split("-")) {
            if (!number.isBlank()) {
                hexes.add(Coords.parseHexNumber(number));
            }
        }
        return hexes;
    }

    /** EDT: whether the bot takes CASPAR's unit orders rather than Princess's waypoints. */
    private boolean caspar(int botId) {
        Game game = source.currentView().game;
        Player bot = game.getPlayer(botId);
        return bot != null && game.getBotTypes().getOrDefault(bot.getName(), AIType.PRINCESS) == AIType.CASPAR;
    }

    private static BehaviorSettings settings(Game game, int botId) {
        Player bot = game.getPlayer(botId);
        return bot == null || game.getBotSettings() == null ? null : game.getBotSettings().get(bot.getName());
    }

    private void send(int botId, Control control, BehaviorChange change) {
        send(botId, control, List.of(change));
    }

    private void send(int botId, Control control, List<BehaviorChange> changes) {
        LOGGER.debug("Bot {}: {} {}", botId, control, changes);
        sender.behavior(botId, new ArrayList<>(changes));
    }

    private List<Player> enemyPlayers(int botId) {
        Game game = source.currentView().game;
        Player bot = game.getPlayer(botId);
        return bot == null ? List.of() : game.getPlayersList().stream()
              .filter(player -> player.isEnemyOf(bot) && !player.isObserver()).toList();
    }
}
