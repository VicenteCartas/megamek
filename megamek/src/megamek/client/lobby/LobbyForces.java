/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import jakarta.annotation.Nullable;
import megamek.common.Player;
import megamek.common.force.Force;
import megamek.common.force.Forces;
import megamek.common.game.Game;
import megamek.common.interfaces.ForceAssignable;
import megamek.common.loaders.MapSettings;
import megamek.common.options.OptionsConstants;
import megamek.common.units.Entity;

/**
 * The lobby's forces as the local player may see them: every unit's card, the units by owner, and the formations
 * (MegaMek's forces) as a tree. A real blind drop leaves out the enemies' units and formations altogether, as the Swing
 * lobby does; a blind drop keeps them, but their cards show only a rough description ({@link LobbyUnit#hidden()}).
 *
 * @param units      the units' cards, in the chosen sort order
 * @param owners     the units by owner: the local player first, then allies, then local bots, then the others
 * @param formations the formations in tree order
 * @param tree       the formation tree as rows in tree order: top-level formations and the units in none, each
 *                   formation followed by its units and then its sub-formations
 * @param sorts      the unit orders the local player may choose from, as the Swing lobby's unit table names them
 * @param sort       the index of the chosen order in {@code sorts}, or -1
 */
public record LobbyForces(List<LobbyUnit> units, List<Owner> owners, List<Formation> formations, List<Row> tree,
      List<String> sorts, int sort) {

    /** No units and no formations. */
    public static final LobbyForces EMPTY = new LobbyForces(List.of(), List.of(), List.of(), List.of(), List.of(), -1);

    public LobbyForces {
        units = List.copyOf(units);
        owners = List.copyOf(owners);
        formations = List.copyOf(formations);
        tree = List.copyOf(tree);
        sorts = List.copyOf(sorts);
    }

    /**
     * The order the Swing lobby's unit table has the units in, which the snapshot keeps.
     *
     * @param unitIds the unit ids in order; units not in it follow in id order
     * @param sorts   the orders to choose from
     * @param sort    the index of the chosen order, or -1
     */
    public record Order(List<Integer> unitIds, List<String> sorts, int sort) {
        /** The units in id order, with nothing to choose from. */
        public static final Order NONE = new Order(List.of(), List.of(), -1);

        public Order {
            unitIds = List.copyOf(unitIds);
            sorts = List.copyOf(sorts);
        }
    }

    /**
     * One owner's units.
     *
     * @param self    whether this is the local player
     * @param local   whether the owner runs on this machine (the local player or a local bot)
     * @param enemy   whether the owner is the local player's enemy
     * @param hidden  whether a blind drop hides the owner's units; their BV and tonnage are then 0
     * @param bv      the units' total Battle Value
     * @param tons    the units' total weight, rounded
     * @param unitIds the owner's units, in the chosen sort order
     */
    public record Owner(int id, String name, boolean self, boolean local, boolean enemy, boolean hidden, int bv,
          int tons, List<Integer> unitIds) {
        public Owner {
            Objects.requireNonNull(name);
            unitIds = List.copyOf(unitIds);
        }
    }

    /**
     * One formation.
     *
     * @param parentId the formation it is part of, or {@link Force#NO_FORCE} for a top-level formation
     * @param depth    how deep in the tree it sits, 0 at the top
     * @param editable whether the local player may change it
     * @param hidden   whether a blind drop hides its owner's units; its BV and tonnage are then 0
     * @param units    how many units it holds, its sub-formations' included
     * @param bv       the Battle Value of its units, its sub-formations' included
     * @param tons     the weight of its units, its sub-formations' included, rounded
     * @param children how many units and sub-formations it holds directly
     */
    public record Formation(int id, String name, int ownerId, String ownerName, int parentId, int depth,
          boolean editable, boolean enemy, boolean hidden, int units, int bv, int tons, int children) {
        public Formation {
            Objects.requireNonNull(name);
            Objects.requireNonNull(ownerName);
        }
    }

    /**
     * One row of the formation tree: a formation or a unit.
     *
     * @param depth       how deep in the tree it sits, 0 at the top
     * @param formationId the formation the row shows, or {@link Force#NO_FORCE} for a unit
     * @param unitId      the unit the row shows, or -1 for a formation
     * @param parentId    the formation that holds the row, or {@link Force#NO_FORCE} at the top
     */
    public record Row(int depth, int formationId, int unitId, int parentId) {
        /** @return whether the row shows a formation */
        public boolean formation() {
            return formationId != Force.NO_FORCE;
        }
    }

    /** @return the card of unit {@code id}, or null */
    public @Nullable LobbyUnit unit(int id) {
        return units.stream().filter(unit -> unit.id() == id).findFirst().orElse(null);
    }

    /** @return formation {@code id}, or null */
    public @Nullable Formation formation(int id) {
        return formations.stream().filter(formation -> formation.id() == id).findFirst().orElse(null);
    }

    /** @return whether any unit the local player sees has a C3 system */
    public boolean anyC3() {
        return units.stream().anyMatch(LobbyUnit::c3);
    }

    /**
     * Reads the forces from {@code session}'s game. Call it on the thread that changes the game (the Swing thread).
     *
     * @param order the order the units are listed in, and the orders to choose from
     */
    public static LobbyForces of(LobbySession session, Order order) {
        Game game = session.game();
        Player local = session.localPlayer();
        List<Entity> all = game.getEntitiesVector() == null ? List.of() : game.getEntitiesVector();
        LobbyCommands commands = new LobbyCommands(session, null);
        Set<String> bots = session.localBots() == null ? Set.of() : session.localBots().keySet();
        Map<Integer, Integer> rank = new HashMap<>();
        for (int index = 0; index < order.unitIds().size(); index++) {
            rank.putIfAbsent(order.unitIds().get(index), index);
        }
        List<Entity> visible = new ArrayList<>();
        for (Entity entity : all) {
            if (visible(game, local, entity.getOwner())) {
                visible.add(entity);
            }
        }
        visible.sort(Comparator.comparingInt((Entity entity) -> rank.getOrDefault(entity.getId(), Integer.MAX_VALUE))
              .thenComparingInt(Entity::getId));
        int medium = session.client().getMapSettings() == null ? MapSettings.MEDIUM_GROUND
              : session.client().getMapSettings().getMedium();
        List<LobbyUnit> units = new ArrayList<>();
        Map<Integer, List<Entity>> byOwner = new LinkedHashMap<>();
        for (Entity entity : visible) {
            units.add(LobbyUnit.of(entity, game, local, medium, local != null && commands.isEditable(entity)));
            byOwner.computeIfAbsent(entity.getOwnerId(), key -> new ArrayList<>()).add(entity);
        }
        Comparator<Player> ownerOrder = ownerOrder(local, bots);
        List<Owner> owners = new ArrayList<>();
        byOwner.entrySet().stream()
              .sorted((a, b) -> ownerOrder.compare(game.getPlayer(a.getKey()), game.getPlayer(b.getKey())))
              .forEach(entry -> owners.add(owner(game, local, bots, entry.getKey(), entry.getValue())));

        List<Formation> formations = new ArrayList<>();
        List<Row> tree = new ArrayList<>();
        Forces forces = game.getForces();
        if (forces != null) {
            tree(game, forces, local, commands, bots, formations, tree);
        }
        return new LobbyForces(units, owners, formations, tree, order.sorts(), order.sort());
    }

    /** Whether {@code local} sees units of {@code owner} at all: not when a real blind drop hides an enemy's. */
    static boolean visible(Game game, @Nullable Player local, @Nullable Player owner) {
        if (local == null || owner == null || local.isGameMaster() || !local.isEnemyOf(owner)) {
            return true;
        }
        return !game.getOptions().booleanOption(OptionsConstants.BASE_REAL_BLIND_DROP);
    }

    private static Owner owner(Game game, @Nullable Player local, Set<String> bots, int id, List<Entity> units) {
        Player player = game.getPlayer(id);
        boolean hidden = LobbyUnit.hides(game, local, player);
        int bv = 0;
        double tons = 0;
        if (!hidden) {
            for (Entity entity : units) {
                bv += entity.calculateBattleValue();
                tons += entity.getWeight();
            }
        }
        boolean self = local != null && id == local.getId();
        boolean localBot = player != null && bots.contains(player.getName());
        boolean enemy = local != null && player != null && local.isEnemyOf(player);
        return new Owner(id, player == null ? "" : player.getName(), self, self || localBot, enemy, hidden, bv,
              (int) Math.round(tons), units.stream().map(Entity::getId).toList());
    }

    /** Owners as the Swing force tree orders its top level: the local player, allies, local bots, the rest. */
    private static Comparator<Player> ownerOrder(@Nullable Player local, Set<String> bots) {
        return Comparator.comparingInt((Player player) -> ownerRank(player, local, bots))
              .thenComparingInt(player -> player == null ? Integer.MAX_VALUE : player.getId());
    }

    private static int ownerRank(@Nullable Player player, @Nullable Player local, Set<String> bots) {
        if (player == null) {
            return 4;
        } else if (local != null && player.getId() == local.getId()) {
            return 0;
        } else if (local != null && !player.isEnemyOf(local)) {
            return 1;
        }
        return bots.contains(player.getName()) ? 2 : 3;
    }

    /** Fills in the formation tree as the Swing lobby's force tree shows it. */
    private static void tree(Game game, Forces forces, @Nullable Player local, LobbyCommands commands,
          Set<String> bots, List<Formation> formations, List<Row> rows) {
        List<Object> top = new ArrayList<>();
        for (Force force : forces.getTopLevelForces()) {
            if (visible(game, local, forces.getOwner(force))) {
                top.add(force);
            }
        }
        for (Entity entity : ForceAssignable.filterToEntityList(forces.forcelessEntities())) {
            if (visible(game, local, entity.getOwner())) {
                top.add(entity);
            }
        }
        Comparator<Player> ownerOrder = ownerOrder(local, bots);
        top.sort(Comparator.comparing((Object item) -> item instanceof Force force ? forces.getOwner(force)
                    : ((Entity) item).getOwner(), Comparator.nullsLast(ownerOrder))
              .thenComparing(item -> item instanceof Force ? 0 : 1)
              .thenComparingInt(item -> item instanceof Force force ? force.getId() : ((Entity) item).getId()));
        for (Object item : top) {
            if (item instanceof Force force) {
                formation(game, forces, force, 0, local, commands, formations, rows);
            } else {
                rows.add(new Row(0, Force.NO_FORCE, ((Entity) item).getId(), Force.NO_FORCE));
            }
        }
    }

    private static void formation(Game game, Forces forces, Force force, int depth, @Nullable Player local,
          LobbyCommands commands, List<Formation> formations, List<Row> rows) {
        Player owner = forces.getOwner(force);
        boolean hidden = LobbyUnit.hides(game, local, owner);
        List<Entity> all = ForceAssignable.filterToEntityList(forces.getFullEntities(force));
        int bv = 0;
        double tons = 0;
        if (!hidden) {
            for (Entity entity : all) {
                bv += entity.calculateBattleValue();
                tons += entity.getWeight();
            }
        }
        boolean enemy = local != null && owner != null && local.isEnemyOf(owner);
        formations.add(new Formation(force.getId(), force.getName(), owner == null ? Player.PLAYER_NONE
              : owner.getId(), owner == null ? "" : owner.getName(), force.getParentId(), depth,
              local != null && commands.isEditable(force), enemy, hidden, all.size(), bv, (int) Math.round(tons),
              force.getChildCount()));
        rows.add(new Row(depth, force.getId(), -1, force.getParentId()));
        for (int unitId : force.getEntities()) {
            if (game.getEntity(unitId) instanceof Entity entity && visible(game, local, entity.getOwner())) {
                rows.add(new Row(depth + 1, Force.NO_FORCE, unitId, force.getId()));
            }
        }
        for (int subId : force.getSubForces()) {
            Force sub = forces.getForce(subId);
            if (sub != null) {
                formation(game, forces, sub, depth + 1, local, commands, formations, rows);
            }
        }
    }
}
