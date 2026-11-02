/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import jakarta.annotation.Nullable;
import megamek.client.lobby.C3FormationPlanner.ConfigurationShape;
import megamek.client.lobby.C3FormationPlanner.ForcePlan;
import megamek.client.lobby.C3FormationPlanner.LeftOutLance;
import megamek.client.lobby.C3FormationPlanner.PlannedLance;
import megamek.client.lobby.C3FormationPlanner.TemplateAssignment;
import megamek.client.ui.Messages;
import megamek.common.Player;
import megamek.common.game.Game;
import megamek.common.units.Entity;
import megamek.common.util.C3Util;

/**
 * The C3 Network Manager without a toolkit: the local player's unassigned C3 units, the team's networks as trees
 * drawn like the rulebook's C3 Configuration Diagram (CR p.199), and the staged plan cards the configuration templates
 * and Plan Force lay out. Actions apply at once through {@link Commands}, as the Swing dialog's do; the planning state
 * (plan cards, seeded masters, auto-assign) lives here. {@link #picture()} reads the whole board for a view. Runs where
 * the game is read, the Swing thread in the lobby.
 */
public final class C3NetworkBoard {
    private static final String KEY = "C3NetworkManagerDialog.";

    /** Lance masters each configuration needs beyond the company unit (CR p.199): Configs 1-4. */
    private static final int[] LANCE_MASTERS_NEEDED = { 3, 2, 1, 0 };
    /** Slaves each configuration needs (CR p.199): 8 for Config 1 (3+3+2), 9 for Configs 2-4. */
    private static final int[] SLAVES_NEEDED = { 8, 9, 9, 9 };

    /** The lobby's C3 actions the board applies; {@link #of} adapts {@link LobbyCommands}. */
    public interface Commands {
        void disconnect(List<Entity> units);

        void companyMaster(List<Entity> units);

        void lanceMaster(List<Entity> units);

        void joinPeers(List<Entity> units, int masterId, boolean disconnectFirst);

        void connect(List<Entity> units, int masterId, boolean disconnectFirst);

        static Commands of(LobbyCommands commands) {
            return new Commands() {
                @Override
                public void disconnect(List<Entity> units) {
                    commands.c3DisconnectFromNetwork(units);
                }

                @Override
                public void companyMaster(List<Entity> units) {
                    commands.c3SetCompanyMaster(units);
                }

                @Override
                public void lanceMaster(List<Entity> units) {
                    commands.c3SetLanceMaster(units);
                }

                @Override
                public void joinPeers(List<Entity> units, int masterId, boolean disconnectFirst) {
                    commands.c3JoinNh(units, masterId, disconnectFirst);
                }

                @Override
                public void connect(List<Entity> units, int masterId, boolean disconnectFirst) {
                    commands.c3Connect(units, masterId, disconnectFirst);
                }
            };
        }
    }

    /**
     * A node's colour as the rulebook diagram colours it: the company head, the company's own lance, one hue per
     * other lance, the masterless peer networks and muted hints. The view maps them to colour tokens.
     */
    public enum Tint { COMPANY, COMPANY_LANCE, LANCE_1, LANCE_2, LANCE_3, LANCE_4, PEER, MUTED }

    private static final Tint[] LANCE_TINTS = { Tint.LANCE_1, Tint.LANCE_2, Tint.LANCE_3, Tint.LANCE_4 };

    /** The kinds of slot a plan card offers. */
    public enum SlotKind { COMPANY, DIRECT_SLAVE, LANCE_MASTER, LANCE_SLAVE }

    /** Masterless C3 systems; a seed per system lets players start a network by dropping two members on it. */
    public enum PeerSystem {
        C3I("C3i"), NC3("Naval C3"), NOVA("Nova CEWS");

        private final String displayName;

        PeerSystem(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        boolean matches(Entity entity) {
            return switch (this) {
                case C3I -> entity.hasC3i();
                case NC3 -> entity.hasNavalC3();
                case NOVA -> entity.hasNovaCEWS();
            };
        }
    }

    /** Where units dragged (or clicked) onto the board go; {@link #drop} applies them. */
    public sealed interface Target {
        /** A network member, a network's head or a free-slot node: connects the units to that unit. */
        record Unit(int id) implements Target {}

        /** One slot of a plan card. */
        record Slot(int plan, SlotKind kind, int lance, int slot) implements Target {}

        /** A plan card as a whole: fills the units into its open slots, and the other cards'. */
        record Plan(int plan) implements Target {}

        /** A new peer network of the system. */
        record Peer(PeerSystem system) implements Target {}

        /** The network side's open space: masters start new networks there. */
        record NewNetwork() implements Target {}

        /** The roster's open space: units leave their plan slots and networks. */
        record Roster() implements Target {}

        /** A unit in the roster: connects the units to it. */
        record RosterUnit(int id) implements Target {}
    }

    /** An unassigned unit in the roster; {@code planned} while a plan card holds it. */
    public record RosterUnit(int id, String name, String equipment, String label, boolean planned) {}

    /** One system's unassigned units. */
    public record RosterGroup(String title, List<RosterUnit> units) {}

    /** The kinds of node a network or plan card draws. */
    public enum NodeKind { UNIT, FREE, SLOT }

    /**
     * One node of a diagram: a unit, the free links of a master ({@code FREE}) or a plan card's slot ({@code SLOT},
     * with {@code unitId} -1 while empty). {@code role} names the node's part, {@code detail} its id and capacity,
     * and {@code tip} says what fits an open slot.
     */
    public record Node(NodeKind kind, int unitId, String role, String name, String detail, String tip, Tint tint,
          Target target, List<Node> children) {
        /** Every unit id at or under this node. */
        public List<Integer> unitIds() {
            List<Integer> ids = new ArrayList<>();
            collect(this, ids);
            return ids;
        }

        private static void collect(Node node, List<Integer> ids) {
            if (node.unitId >= 0) {
                ids.add(node.unitId);
            }
            node.children.forEach(child -> collect(child, ids));
        }
    }

    /**
     * One network, or a seeded master waiting for its first members ({@code seed}). {@code config} names the book
     * configuration the network builds toward, {@code configComplete} once it is whole.
     */
    public record Network(String title, String config, boolean configComplete, boolean seed, boolean peer,
          int topId, List<Integer> members, List<Node> roots) {}

    /** A staged plan card: its header and the slot tree under its company (or lance master) slot. */
    public record PlanCard(int index, String header, Node root, int filled, int total) {}

    /** The drop target that starts a peer network of {@code system}. */
    public record PeerSeed(PeerSystem system, String label) {}

    /**
     * The whole board as a view draws it. {@code templateLit} is the configuration whose plan shows (0 for none),
     * {@code planFilled} how many slots the cards hold (a new plan asks before replacing them), and
     * {@code selection} the units the board opened with, once.
     */
    public record Picture(List<RosterGroup> roster, List<PlanCard> plans, List<Network> networks,
          List<PeerSeed> peerSeeds, @Nullable String startHint, String status, boolean notice,
          List<String> templateTips, String forcePlan, boolean planForceEnabled, int templateLit,
          boolean planForceLit, boolean peerVisible, boolean buildVisible, boolean discardVisible,
          boolean autoAssignVisible, boolean disconnectVisible, boolean autoAssign, int planFilled,
          boolean resettable, List<Integer> selection) {
        /** Every unassigned unit, in roster order. */
        public List<RosterUnit> rosterUnits() {
            return roster.stream().flatMap(group -> group.units().stream()).toList();
        }
    }

    /**
     * A plan of one of the four book configurations, a remainder lance ({@code 0}) or an all-master lance
     * ({@code -1}). Filling slots changes nothing in the game; {@link #build} applies the cards.
     */
    private static final class Blueprint {
        private final int configurationNumber;
        private Integer companyId;
        private final Integer[] directSlaves;
        private final Integer[] lanceMasters;
        private final Integer[][] lanceSlaves;

        private Blueprint(int configurationNumber, int directSlaveCount, int[] lanceSlaveCounts) {
            this.configurationNumber = configurationNumber;
            directSlaves = new Integer[directSlaveCount];
            lanceMasters = new Integer[lanceSlaveCounts.length];
            lanceSlaves = new Integer[lanceSlaveCounts.length][];
            for (int lance = 0; lance < lanceSlaveCounts.length; lance++) {
                lanceSlaves[lance] = new Integer[lanceSlaveCounts[lance]];
            }
        }

        static Blueprint forConfiguration(int configurationNumber) {
            ConfigurationShape shape = ConfigurationShape.of(configurationNumber);
            return new Blueprint(shape.configurationNumber(), shape.directSlaveCount(), shape.lanceSlaveCounts());
        }

        static Blueprint slaveLance() {
            return new Blueprint(0, Entity.MAX_C3M_SUBORDINATES, new int[0]);
        }

        static Blueprint allMasterLance() {
            return new Blueprint(-1, 0, new int[] { 0, 0, 0 });
        }

        boolean isCompanyConfiguration() {
            return configurationNumber >= 1;
        }

        List<Slot> slots() {
            List<Slot> slots = new ArrayList<>();
            slots.add(new Slot(SlotKind.COMPANY, -1, -1));
            for (int index = 0; index < directSlaves.length; index++) {
                slots.add(new Slot(SlotKind.DIRECT_SLAVE, -1, index));
            }
            for (int lance = 0; lance < lanceMasters.length; lance++) {
                slots.add(new Slot(SlotKind.LANCE_MASTER, lance, -1));
                for (int index = 0; index < lanceSlaves[lance].length; index++) {
                    slots.add(new Slot(SlotKind.LANCE_SLAVE, lance, index));
                }
            }
            return slots;
        }

        int totalSlots() {
            return slots().size();
        }

        int filledCount() {
            return (int) slots().stream().filter(slot -> value(slot) != null).count();
        }

        boolean containsUnit(int id) {
            return slots().stream().anyMatch(slot -> Objects.equals(value(slot), id));
        }

        void clearSlots() {
            companyId = null;
            Arrays.fill(directSlaves, null);
            Arrays.fill(lanceMasters, null);
            for (Integer[] lance : lanceSlaves) {
                Arrays.fill(lance, null);
            }
        }

        void unassign(int id) {
            for (Slot slot : slots()) {
                if (Objects.equals(value(slot), id)) {
                    set(slot, null);
                }
            }
        }

        @Nullable
        Integer value(Slot slot) {
            return switch (slot.kind()) {
                case COMPANY -> companyId;
                case DIRECT_SLAVE -> directSlaves[slot.index()];
                case LANCE_MASTER -> lanceMasters[slot.lance()];
                case LANCE_SLAVE -> lanceSlaves[slot.lance()][slot.index()];
            };
        }

        void set(Slot slot, @Nullable Integer id) {
            switch (slot.kind()) {
                case COMPANY -> companyId = id;
                case DIRECT_SLAVE -> directSlaves[slot.index()] = id;
                case LANCE_MASTER -> lanceMasters[slot.lance()] = id;
                case LANCE_SLAVE -> lanceSlaves[slot.lance()][slot.index()] = id;
            }
        }
    }

    private record Slot(SlotKind kind, int lance, int index) {}

    private final Supplier<Game> game;
    private final int localPlayerId;
    private final Commands commands;
    private final List<Blueprint> plans = new ArrayList<>();
    private final Set<Integer> seededMasterIds = new LinkedHashSet<>();
    private final List<Integer> initialSelection = new ArrayList<>();
    private boolean plansFromForcePlan;
    private boolean autoAssign = true;
    private @Nullable String notice;

    /**
     * @param game          the game, read afresh for each action and picture
     * @param localPlayerId the player whose units the board manages
     * @param selection     the units the manager opens on, selected in the first picture
     */
    public C3NetworkBoard(Supplier<Game> game, int localPlayerId, Commands commands, Collection<Integer> selection) {
        this.game = game;
        this.localPlayerId = localPlayerId;
        this.commands = commands;
        initialSelection.addAll(selection);
    }

    // ---- Actions ----

    /** Lays out the configuration template, pre-filled from the unassigned force with auto-assign on. */
    public void startTemplate(int configurationNumber) {
        notice = null;
        plans.clear();
        plansFromForcePlan = false;
        Blueprint blueprint = Blueprint.forConfiguration(configurationNumber);
        plans.add(blueprint);
        if (autoAssign) {
            TemplateAssignment assignment = planner().planTemplate(configurationNumber);
            applyAssignment(blueprint, assignment);
            showLeftOut(assignment.leftOut(), configurationNumber);
        }
    }

    /** Lays out the whole force as plan cards, most consolidated configuration first; nothing when none fits. */
    public void planForce() {
        notice = null;
        planForce(autoAssign);
    }

    /** Fills empty plan cards as auto-assign would have when they were laid out. */
    public void autoAssignPlans() {
        notice = null;
        boolean configurationCard = plans.size() == 1 && plans.get(0).isCompanyConfiguration();
        if (plansFromForcePlan || !configurationCard) {
            planForce(true);
            return;
        }
        Blueprint blueprint = plans.get(0);
        TemplateAssignment assignment = planner().planTemplate(blueprint.configurationNumber);
        applyAssignment(blueprint, assignment);
        showLeftOut(assignment.leftOut(), blueprint.configurationNumber);
    }

    /**
     * Applies every plan card in dependency order (company master, direct slaves, lance masters, each lance's
     * slaves) and discards the cards; cards without their head and slaves without their lance master are skipped.
     */
    public void build() {
        notice = null;
        pruneStale();
        int orphans = 0;
        boolean built = false;
        for (Blueprint blueprint : plans) {
            Entity company = (blueprint.companyId == null) ? null : game().getEntity(blueprint.companyId);
            if (company == null) {
                orphans += blueprint.filledCount();
                continue;
            }
            built = true;
            if (blueprint.isCompanyConfiguration()) {
                commands.companyMaster(List.of(company));
            }
            List<Entity> directSlaves = resolve(blueprint.directSlaves);
            if (!directSlaves.isEmpty()) {
                commands.connect(directSlaves, company.getId(), false);
            }
            List<Entity> lanceMasters = resolve(blueprint.lanceMasters);
            if (!lanceMasters.isEmpty()) {
                commands.connect(lanceMasters, company.getId(), false);
            }
            for (int lance = 0; lance < blueprint.lanceMasters.length; lance++) {
                List<Entity> slaves = resolve(blueprint.lanceSlaves[lance]);
                Integer lanceMasterId = blueprint.lanceMasters[lance];
                if (lanceMasterId == null) {
                    orphans += slaves.size();
                } else if (!slaves.isEmpty()) {
                    commands.connect(slaves, lanceMasterId, false);
                }
            }
        }
        if (!built) {
            notice = text("buildNeedsCompany");
            return;
        }
        plans.clear();
        if (orphans > 0) {
            notice = text("builtOrphans", orphans);
        }
    }

    /** Throws away the plan cards and seeded masters; built networks stay. */
    public void discardPlan() {
        notice = null;
        plans.clear();
        seededMasterIds.clear();
    }

    /** Disconnects the units from their networks. */
    public void disconnect(Collection<Integer> ids) {
        notice = null;
        List<Entity> units = entities(ids);
        if (units.isEmpty()) {
            notice = text("selectNetworkUnits");
            return;
        }
        commands.disconnect(units);
    }

    /** Links two or more units of one masterless system into a peer network. */
    public void formPeerNetwork(Collection<Integer> ids) {
        notice = null;
        List<Entity> units = entities(ids);
        if (units.size() < 2) {
            notice = text("selectTwoPeers");
            return;
        }
        commands.joinPeers(units, units.get(0).getId(), true);
    }

    /** Whether Reset All has anything to do, so the view asks before it does. */
    public boolean resettable() {
        Reset reset = readReset();
        return !reset.disconnect.isEmpty() || !reset.lone.isEmpty() || !seededMasterIds.isEmpty()
              || plansFilled() > 0;
    }

    /**
     * Disconnects every network of the player's, demotes company masters left alone, clears seeds and empties the
     * plan cards, keeping their layout. Peer-net name units fall back by themselves; allies are not touched.
     */
    public void reset() {
        notice = null;
        if (!resettable()) {
            notice = text("nothingToReset");
            return;
        }
        Reset reset = readReset();
        seededMasterIds.clear();
        plans.forEach(Blueprint::clearSlots);
        if (!reset.disconnect.isEmpty()) {
            commands.disconnect(reset.disconnect);
        }
        if (!reset.lone.isEmpty()) {
            commands.lanceMaster(reset.lone);
        }
    }

    /** Whether new templates and Plan Force come pre-filled. */
    public void autoAssign(boolean on) {
        autoAssign = on;
    }

    /** Puts the units on {@code target}, as a drop (or a click with them chosen) does. */
    public void drop(List<Integer> ids, Target target) {
        notice = null;
        List<Entity> units = entities(ids);
        if (units.isEmpty()) {
            return;
        }
        switch (target) {
            case Target.Unit unit -> {
                Entity entity = game().getEntity(unit.id());
                if (entity != null) {
                    connectTo(units, entity);
                }
            }
            case Target.Slot slot -> dropOnSlot(units, slot);
            case Target.Plan plan -> {
                if (plan.plan() < plans.size()) {
                    autoFill(units);
                }
            }
            case Target.Peer peer -> formPeerNetwork(units, peer.system());
            case Target.NewNetwork ignored -> seed(units);
            case Target.Roster ignored -> returnToRoster(units);
            case Target.RosterUnit unit -> {
                Entity entity = game().getEntity(unit.id());
                if (entity != null) {
                    dropOnRosterUnit(units, entity);
                }
            }
        }
    }

    // ---- The picture ----

    /** Reads the board as it stands for a view, the units it opened on selected the first time. */
    public Picture picture() {
        Game game = game();
        List<Entity> seeds = new ArrayList<>();
        List<Entity> masters = new ArrayList<>();
        List<Entity> slaves = new ArrayList<>();
        List<Entity> c3i = new ArrayList<>();
        List<Entity> naval = new ArrayList<>();
        List<Entity> nova = new ArrayList<>();
        for (Entity entity : sorted(game.inGameTWEntities())) {
            if (entity.getOwnerId() != localPlayerId || !entity.hasAnyC3System() || isNetworked(entity)) {
                continue;
            }
            if (entity.getOperableC3MCount() > 0) {
                if (seededMasterIds.contains(entity.getId()) || entity.isC3CompanyCommander()) {
                    seeds.add(entity);
                } else {
                    masters.add(entity);
                }
            } else if (entity.hasC3i()) {
                c3i.add(entity);
            } else if (entity.hasNavalC3()) {
                naval.add(entity);
            } else if (entity.hasNovaCEWS()) {
                nova.add(entity);
            } else {
                slaves.add(entity);
            }
        }
        List<RosterGroup> roster = new ArrayList<>();
        group(roster, "groupMasters", masters);
        group(roster, "groupSlaves", slaves);
        group(roster, "groupC3i", c3i);
        group(roster, "groupNC3", naval);
        group(roster, "groupNova", nova);

        pruneStale();
        List<PlanCard> cards = new ArrayList<>();
        for (int index = 0; index < plans.size(); index++) {
            cards.add(card(index));
        }
        List<Network> networks = new ArrayList<>();
        Map<String, List<Entity>> built = networks();
        built.values().forEach(members -> networks.add(network(members)));
        seeds.forEach(seed -> networks.add(network(List.of(seed))));

        List<RosterUnit> unassigned = roster.stream().flatMap(group -> group.units().stream()).toList();
        List<PeerSeed> peerSeeds = new ArrayList<>();
        for (PeerSystem system : PeerSystem.values()) {
            long matching = unassigned.stream().map(unit -> game.getEntity(unit.id()))
                  .filter(entity -> entity != null && system.matches(entity)).count();
            if (matching >= 2) {
                peerSeeds.add(new PeerSeed(system, text("peerSeed", system.displayName())));
            }
        }
        String hint = (cards.isEmpty() && networks.isEmpty() && peerSeeds.isEmpty()) ? text("startHint") : null;

        List<Entity> allMasters = new ArrayList<>(masters);
        allMasters.addAll(seeds);
        ForcePlan forcePlan = planner().planForce();
        boolean configurationPlan = !plans.isEmpty() && !plansFromForcePlan;
        int filled = plansFilled();
        List<Integer> selection = List.copyOf(initialSelection);
        initialSelection.clear();
        String status = (notice != null) ? notice : text("boardStatus", unassigned.size(), built.size() + seeds.size());
        return new Picture(List.copyOf(roster), List.copyOf(cards), List.copyOf(networks), List.copyOf(peerSeeds),
              hint, status, notice != null, templateTips(allMasters, slaves), forcePlanText(forcePlan),
              !forcePlan.isEmpty(), configurationPlan ? plans.get(0).configurationNumber : 0,
              !plans.isEmpty() && plansFromForcePlan, !c3i.isEmpty() || !naval.isEmpty() || !nova.isEmpty(),
              !plans.isEmpty(), !plans.isEmpty() || !seeds.isEmpty(), !plans.isEmpty() && filled == 0,
              !built.isEmpty(), autoAssign, filled, resettable(), selection);
    }

    // ---- Drops ----

    private void dropOnSlot(List<Entity> units, Target.Slot target) {
        if (target.plan() >= plans.size()) {
            return;
        }
        Blueprint blueprint = plans.get(target.plan());
        Slot slot = new Slot(target.kind(), target.lance(), target.slot());
        Entity first = units.get(0);
        if (!fits(first, blueprint, slot)) {
            notice = text("wrongSlotUnit");
            return;
        }
        unassign(first.getId());
        blueprint.set(slot, first.getId());
        if (units.size() > 1) {
            autoFill(units.subList(1, units.size()));
        }
    }

    /** Fills each unit into the first open slot it fits, across the cards in order. */
    private void autoFill(List<Entity> units) {
        for (Entity unit : units) {
            for (Blueprint blueprint : plans) {
                Slot open = firstOpenSlot(blueprint, unit);
                if (open != null) {
                    unassign(unit.getId());
                    blueprint.set(open, unit.getId());
                    break;
                }
            }
        }
    }

    /** A card's first open slot the unit fits: company, lance masters, direct slaves, lance slaves. */
    private @Nullable Slot firstOpenSlot(Blueprint blueprint, Entity unit) {
        List<Slot> order = new ArrayList<>();
        order.add(new Slot(SlotKind.COMPANY, -1, -1));
        for (int lance = 0; lance < blueprint.lanceMasters.length; lance++) {
            order.add(new Slot(SlotKind.LANCE_MASTER, lance, -1));
        }
        for (int index = 0; index < blueprint.directSlaves.length; index++) {
            order.add(new Slot(SlotKind.DIRECT_SLAVE, -1, index));
        }
        for (int lance = 0; lance < blueprint.lanceSlaves.length; lance++) {
            for (int index = 0; index < blueprint.lanceSlaves[lance].length; index++) {
                order.add(new Slot(SlotKind.LANCE_SLAVE, lance, index));
            }
        }
        return order.stream().filter(slot -> blueprint.value(slot) == null && fits(unit, blueprint, slot))
              .findFirst().orElse(null);
    }

    private boolean fits(Entity unit, Blueprint blueprint, Slot slot) {
        if (unit.hasNhC3() || unit.getOwnerId() != localPlayerId || isNetworked(unit)) {
            return false;
        }
        return switch (slot.kind()) {
            // Extra computers idle, so a triple master can head Configuration 1 or 2 too
            case COMPANY -> blueprint.isCompanyConfiguration()
                  ? unit.getOperableC3MCount() >= blueprint.configurationNumber
                  : unit.getOperableC3MCount() > 0;
            case LANCE_MASTER -> unit.getOperableC3MCount() > 0;
            case DIRECT_SLAVE, LANCE_SLAVE -> unit.hasC3S() && unit.getOperableC3MCount() == 0;
        };
    }

    /**
     * Connects the units to the target; an independent target receiving masters, or carrying two or more
     * computers, becomes a company master first so its full capacity opens.
     */
    private void connectTo(List<Entity> joiners, Entity target) {
        List<Entity> units = new ArrayList<>(joiners);
        units.removeIf(unit -> unit.getId() == target.getId());
        if (units.isEmpty()) {
            return;
        }
        if (target.hasNhC3()) {
            commands.joinPeers(units, target.getId(), false);
            return;
        }
        boolean masters = units.stream().anyMatch(unit -> unit.getOperableC3MCount() > 0);
        boolean subordinate = target.getC3Master() != null && !target.isC3CompanyCommander();
        if (!target.isC3CompanyCommander() && !subordinate && (masters || target.getOperableC3MCount() >= 2)) {
            commands.companyMaster(List.of(target));
        }
        commands.connect(units, target.getId(), false);
    }

    /** A master target forms or grows its network; the same peer system forms a peer network; else a hint. */
    private void dropOnRosterUnit(List<Entity> joiners, Entity target) {
        List<Entity> units = new ArrayList<>(joiners);
        units.removeIf(unit -> unit.getId() == target.getId());
        if (units.isEmpty()) {
            return;
        }
        if (target.getOperableC3MCount() > 0) {
            connectTo(units, target);
            return;
        }
        if (target.hasNhC3()) {
            List<Entity> peers = new ArrayList<>();
            peers.add(target);
            units.stream().filter(unit -> C3Util.sameNhC3System(target, unit)).forEach(peers::add);
            if (peers.size() >= 2) {
                commands.joinPeers(peers, target.getId(), true);
                return;
            }
        }
        notice = text("dropOnMaster");
    }

    private void formPeerNetwork(List<Entity> units, PeerSystem system) {
        List<Entity> matching = units.stream().filter(system::matches).toList();
        if (matching.size() < 2) {
            notice = text("selectTwoPeers");
            return;
        }
        commands.joinPeers(matching, matching.get(0).getId(), true);
    }

    private void seed(List<Entity> units) {
        boolean seeded = false;
        for (Entity unit : units) {
            if (unit.getOperableC3MCount() > 0 && unit.getOwnerId() == localPlayerId && !isNetworked(unit)) {
                seededMasterIds.add(unit.getId());
                seeded = true;
            }
        }
        if (!seeded) {
            notice = text("dropMasterToStart");
        }
    }

    /** Takes the units out of their plan slots and networks; seeds and lone company masters become plain units. */
    private void returnToRoster(List<Entity> units) {
        List<Entity> networked = new ArrayList<>();
        List<Entity> lone = new ArrayList<>();
        for (Entity unit : units) {
            unassign(unit.getId());
            seededMasterIds.remove(unit.getId());
            if (isNetworked(unit)) {
                networked.add(unit);
            } else if (unit.isC3CompanyCommander()) {
                lone.add(unit);
            }
        }
        if (!networked.isEmpty()) {
            commands.disconnect(networked);
        }
        if (!lone.isEmpty()) {
            commands.lanceMaster(lone);
        }
    }

    // ---- Plans ----

    private void planForce(boolean filled) {
        ForcePlan plan = planner().planForce();
        if (plan.isEmpty()) {
            return;
        }
        plans.clear();
        plansFromForcePlan = true;
        for (TemplateAssignment company : plan.companies()) {
            Blueprint card = Blueprint.forConfiguration(company.configurationNumber());
            if (filled) {
                applyAssignment(card, company);
            }
            plans.add(card);
        }
        for (PlannedLance lance : plan.lances()) {
            Blueprint card = lance.allMasters() ? Blueprint.allMasterLance() : Blueprint.slaveLance();
            if (filled) {
                card.companyId = lance.master().getId();
                for (int index = 0; index < lance.members().size(); index++) {
                    Integer[] slots = lance.allMasters() ? card.lanceMasters : card.directSlaves;
                    slots[index] = lance.members().get(index).getId();
                }
            }
            plans.add(card);
        }
        if (filled) {
            showLeftOut(plan.leftOut(), 0);
        }
    }

    private static void applyAssignment(Blueprint card, TemplateAssignment assignment) {
        card.companyId = idOf(assignment.head());
        for (int index = 0; index < card.directSlaves.length; index++) {
            card.directSlaves[index] = idOf(assignment.directSlaves()[index]);
        }
        for (int lance = 0; lance < card.lanceMasters.length; lance++) {
            card.lanceMasters[lance] = idOf(assignment.lanceMasters()[lance]);
            for (int index = 0; index < card.lanceSlaves[lance].length; index++) {
                card.lanceSlaves[lance][index] = idOf(assignment.lanceSlaves()[lance][index]);
            }
        }
    }

    private static @Nullable Integer idOf(@Nullable Entity unit) {
        return (unit == null) ? null : unit.getId();
    }

    /** Names the lobby lances auto-assign kept out whole, with the reason. */
    private void showLeftOut(List<LeftOutLance> leftOut, int configurationNumber) {
        if (leftOut.isEmpty()) {
            return;
        }
        List<String> parts = leftOut.stream().map(lance -> text("leftOut." + lance.reason().name(),
              lance.lanceName(), lance.masterCount(), lance.slaveCount(), configurationNumber)).toList();
        notice = text("leftOutLances", String.join("; ", parts));
    }

    private int plansFilled() {
        return plans.stream().mapToInt(Blueprint::filledCount).sum();
    }

    private void unassign(int id) {
        plans.forEach(plan -> plan.unassign(id));
    }

    private boolean planned(int id) {
        return plans.stream().anyMatch(plan -> plan.containsUnit(id));
    }

    /** Drops slots whose unit got networked outside the plans. */
    private void pruneStale() {
        for (Entity entity : game().inGameTWEntities()) {
            if (isNetworked(entity) && planned(entity.getId())) {
                unassign(entity.getId());
            }
        }
    }

    private PlanCard card(int index) {
        Blueprint blueprint = plans.get(index);
        String header;
        if (blueprint.isCompanyConfiguration()) {
            header = text("planHeader", blueprint.configurationNumber, blueprint.filledCount(),
                  blueprint.totalSlots());
        } else if (blueprint.configurationNumber == 0) {
            header = text("planHeaderLance", blueprint.filledCount(), blueprint.totalSlots());
        } else {
            header = text("planHeaderAllMaster", blueprint.filledCount(), blueprint.totalSlots());
        }
        List<Node> children = new ArrayList<>();
        for (int slot = 0; slot < blueprint.directSlaves.length; slot++) {
            children.add(slotNode(index, blueprint, new Slot(SlotKind.DIRECT_SLAVE, -1, slot), List.of()));
        }
        for (int lance : lanceOrder(blueprint)) {
            List<Node> lanceSlaves = new ArrayList<>();
            for (int slot = 0; slot < blueprint.lanceSlaves[lance].length; slot++) {
                lanceSlaves.add(slotNode(index, blueprint, new Slot(SlotKind.LANCE_SLAVE, lance, slot), List.of()));
            }
            children.add(slotNode(index, blueprint, new Slot(SlotKind.LANCE_MASTER, lance, -1), lanceSlaves));
        }
        Node root = slotNode(index, blueprint, new Slot(SlotKind.COMPANY, -1, -1), children);
        return new PlanCard(index, header, root, blueprint.filledCount(), blueprint.totalSlots());
    }

    /** The card's lances in display order: Configuration 1's short company lance first. */
    private static List<Integer> lanceOrder(Blueprint blueprint) {
        int companyLance = blueprint.isCompanyConfiguration()
              ? ConfigurationShape.of(blueprint.configurationNumber).companyLanceIndex() : -1;
        List<Integer> order = new ArrayList<>();
        if (companyLance >= 0) {
            order.add(companyLance);
        }
        for (int lance = 0; lance < blueprint.lanceMasters.length; lance++) {
            if (lance != companyLance) {
                order.add(lance);
            }
        }
        return order;
    }

    private Node slotNode(int index, Blueprint blueprint, Slot slot, List<Node> children) {
        Tint tint = slotTint(blueprint, slot);
        Integer id = blueprint.value(slot);
        Entity unit = (id == null) ? null : game().getEntity(id);
        String role = text(switch (slot.kind()) {
            case COMPANY -> blueprint.isCompanyConfiguration() ? "roleCompanyMaster" : "roleLanceMaster";
            case LANCE_MASTER -> "roleLanceMaster";
            case DIRECT_SLAVE, LANCE_SLAVE -> "roleSlave";
        });
        Target target = new Target.Slot(index, slot.kind(), slot.lance(), slot.index());
        String tip = switch (slot.kind()) {
            case COMPANY -> text("slotCompanyEmpty", blueprint.configurationNumber);
            case LANCE_MASTER -> text("slotLanceMasterEmpty");
            case DIRECT_SLAVE, LANCE_SLAVE -> text("slotSlaveEmpty");
        };
        if (unit == null) {
            return new Node(NodeKind.SLOT, -1, role, text("slotOpen"), "", tip, tint, target, children);
        }
        return new Node(NodeKind.SLOT, unit.getId(), role, unit.getShortNameRaw(),
              "#" + unit.getId() + " " + text("plannedSuffix"), tip, tint, target, children);
    }

    /** A plan slot's tint, as the rulebook diagram colours the configuration and as built networks show it. */
    private static Tint slotTint(Blueprint blueprint, Slot slot) {
        if (!blueprint.isCompanyConfiguration()) {
            return (slot.kind() == SlotKind.COMPANY || slot.kind() == SlotKind.DIRECT_SLAVE) ? Tint.COMPANY
                  : LANCE_TINTS[slot.lance() % LANCE_TINTS.length];
        }
        return switch (slot.kind()) {
            case COMPANY -> Tint.COMPANY;
            case DIRECT_SLAVE -> headLanceTint(slot.index() / Entity.MAX_C3M_SUBORDINATES,
                  blueprint.lanceMasters.length);
            case LANCE_MASTER, LANCE_SLAVE -> (blueprint.configurationNumber == 1
                  && slot.lance() == blueprint.lanceMasters.length - 1) ? Tint.COMPANY_LANCE
                  : LANCE_TINTS[slot.lance() % LANCE_TINTS.length];
        };
    }

    /** A head-run block of three slaves: the first is the company's own lance, later ones follow the lances. */
    private static Tint headLanceTint(int block, int lanceMasterCount) {
        return (block == 0) ? Tint.COMPANY_LANCE : LANCE_TINTS[(lanceMasterCount + block - 1) % LANCE_TINTS.length];
    }

    // ---- Networks ----

    /** The networks (two or more members) among the player's team, by net id, members by id. */
    private Map<String, List<Entity>> networks() {
        Game game = game();
        Player local = game.getPlayer(localPlayerId);
        Map<String, List<Entity>> networks = new LinkedHashMap<>();
        for (Entity entity : sorted(game.inGameTWEntities())) {
            String netId = entity.getC3NetId();
            if (netId == null || !entity.hasAnyC3System() || entity.getOwner() == null
                  || (local != null && entity.getOwner().isEnemyOf(local))) {
                continue;
            }
            networks.computeIfAbsent(netId, unused -> new ArrayList<>()).add(entity);
        }
        networks.values().removeIf(members -> members.size() < 2);
        return networks;
    }

    private Network network(List<Entity> members) {
        Entity top = topUnit(members);
        List<Integer> ids = members.stream().map(Entity::getId).toList();
        boolean seed = members.size() == 1;
        String title;
        if (seed) {
            title = text("seedSummary", top.getShortNameRaw(), top.getId());
        } else {
            String system = top.hasC3i() ? "C3i" : top.hasNavalC3() ? "NC3" : top.hasNovaCEWS() ? "Nova CEWS" : "C3";
            title = text("networkSummary", top.getShortNameRaw(), system, members.size(),
                  Math.min(5 * members.size(), 40));
        }
        String[] config = configuration(members);
        boolean peer = members.get(0).hasNhC3();
        return new Network(title, config[0], config[1] != null, seed, peer, top.getId(), ids, roots(members));
    }

    /** The network's head: the top of its master chain, or the first member of a peer network. */
    private static Entity topUnit(List<Entity> members) {
        for (Entity member : members) {
            Entity master = member.getC3Master();
            if ((master == null || master.getId() == member.getId())
                  && (member.hasC3M() || member.hasC3MM() || member.hasNhC3())) {
                return member;
            }
        }
        return members.get(0);
    }

    /**
     * The book configuration the network builds toward, read from its company master's structure, and whether it is
     * whole (the second element non-null); empty without a company master or past the book's size.
     */
    private static String[] configuration(List<Entity> members) {
        Entity commander = members.stream().filter(member -> !member.hasNhC3() && member.isC3CompanyCommander())
              .findFirst().orElse(null);
        if (commander == null) {
            return new String[] { "", null };
        }
        int directSlaves = 0;
        for (Entity member : members) {
            if (!member.equals(commander) && member.C3MasterIs(commander) && member.hasC3S()) {
                directSlaves++;
            }
        }
        int number = Math.min(1 + (directSlaves + 2) / 3, Math.min(commander.getOperableC3MCount(), 4));
        int canonical = switch (number) {
            case 3 -> 11;
            case 4 -> 10;
            default -> 12;
        };
        if (members.size() > canonical) {
            return new String[] { "", null };
        }
        if (members.size() == canonical) {
            return new String[] { plain(text("configComplete", number, members.size())), "" };
        }
        int missingMasters = commander.calculateFreeC3MNodes();
        int missingSlaves = Math.max(0, canonical - members.size() - missingMasters);
        List<String> missing = new ArrayList<>();
        if (missingMasters > 0) {
            missing.add(text("configNeedsMasters", missingMasters));
        }
        if (missingSlaves > 0) {
            missing.add(text("configNeedsSlaves", missingSlaves));
        }
        return new String[] { plain(text("configProgress", number, members.size(), canonical,
              String.join(", ", missing))), null };
    }

    /** The configuration lines without the separator the Swing tree puts before them. */
    private static String plain(String line) {
        return line.startsWith("| ") ? line.substring(2) : line;
    }

    /**
     * The network's trees, laid out as the rulebook diagram: peer networks flat, then their free links; masters with
     * their own slaves, free slave links, then their lances; company masters with their head slaves in blocks of
     * three, Configuration 1's company lance, the other lances and their free master links.
     */
    private List<Node> roots(List<Entity> members) {
        // Free counts are capped by the 12-unit network limit (CR p.198)
        int remaining = Math.max(0, Entity.MAX_C3_NODES - members.size());
        Entity first = members.get(0);
        if (first.hasNhC3()) {
            List<Node> peers = new ArrayList<>();
            for (Entity member : members) {
                peers.add(member(member, "rolePeer", Tint.PEER, Integer.MAX_VALUE, List.of()));
            }
            int free = first.calculateFreeC3Nodes();
            if (free > 0) {
                peers.add(free(first.getId(), false, free, Tint.PEER));
            }
            return peers;
        }
        Map<Integer, List<Entity>> children = new LinkedHashMap<>();
        List<Entity> roots = new ArrayList<>();
        Set<Integer> listed = new HashSet<>(members.stream().map(Entity::getId).toList());
        for (Entity member : members) {
            Entity master = member.getC3Master();
            if (master != null && master.getId() != member.getId() && listed.contains(master.getId())) {
                children.computeIfAbsent(master.getId(), unused -> new ArrayList<>()).add(member);
            } else {
                roots.add(member);
            }
        }
        int[] lanceCounter = { 0 };
        List<Node> nodes = new ArrayList<>();
        for (Entity root : roots) {
            Tint tint = root.isC3CompanyCommander() ? Tint.COMPANY
                  : LANCE_TINTS[lanceCounter[0]++ % LANCE_TINTS.length];
            nodes.add(subtree(root, children, tint, lanceCounter, remaining));
        }
        return nodes;
    }

    private Node company(Entity company, Map<Integer, List<Entity>> children, int[] lanceCounter, int remaining) {
        List<Entity> headSlaves = new ArrayList<>();
        List<Entity> lanceMasters = new ArrayList<>();
        for (Entity child : children.getOrDefault(company.getId(), List.of())) {
            (child.getOperableC3MCount() > 0 ? lanceMasters : headSlaves).add(child);
        }
        List<Node> nodes = new ArrayList<>();
        for (int index = 0; index < headSlaves.size(); index++) {
            Tint tint = headLanceTint(index / Entity.MAX_C3M_SUBORDINATES, lanceMasters.size());
            nodes.add(subtree(headSlaves.get(index), children, tint, lanceCounter, remaining));
        }
        int freeSlaves = Math.min(company.calculateFreeC3Nodes(), remaining);
        if (freeSlaves > 0) {
            nodes.add(free(company.getId(), false, freeSlaves,
                  headLanceTint(headSlaves.size() / Entity.MAX_C3M_SUBORDINATES, lanceMasters.size())));
        }
        Entity companyLance = configuration1CompanyLance(headSlaves, lanceMasters, children);
        if (companyLance != null) {
            nodes.add(subtree(companyLance, children, Tint.COMPANY_LANCE, lanceCounter, remaining));
        }
        int other = 0;
        for (Entity lanceMaster : lanceMasters) {
            if (lanceMaster != companyLance) {
                nodes.add(subtree(lanceMaster, children, LANCE_TINTS[other++ % LANCE_TINTS.length], lanceCounter,
                      remaining));
            }
        }
        int freeMasters = Math.min(company.calculateFreeC3MNodes(), remaining);
        if (freeMasters > 0) {
            nodes.add(free(company.getId(), true, freeMasters, Tint.COMPANY));
        }
        return member(company, "roleCompanyMaster", Tint.COMPANY, remaining, nodes);
    }

    /** Configuration 1's company lance: with no head slaves and three lance masters, the one with fewest slaves. */
    private static @Nullable Entity configuration1CompanyLance(List<Entity> headSlaves, List<Entity> lanceMasters,
          Map<Integer, List<Entity>> children) {
        if (!headSlaves.isEmpty() || lanceMasters.size() != Entity.MAX_C3M_SUBORDINATES) {
            return null;
        }
        Entity shortest = null;
        int fewest = Integer.MAX_VALUE;
        for (Entity lanceMaster : lanceMasters) {
            int count = children.getOrDefault(lanceMaster.getId(), List.of()).size();
            if (count <= fewest) {
                shortest = lanceMaster;
                fewest = count;
            }
        }
        return shortest;
    }

    private Node subtree(Entity entity, Map<Integer, List<Entity>> children, Tint tint, int[] lanceCounter,
          int remaining) {
        if (entity.isC3CompanyCommander()) {
            return company(entity, children, lanceCounter, remaining);
        }
        boolean master = entity.getOperableC3MCount() > 0;
        List<Entity> under = children.getOrDefault(entity.getId(), List.of());
        List<Node> nodes = new ArrayList<>();
        for (Entity child : under) {
            if (child.getOperableC3MCount() == 0) {
                nodes.add(subtree(child, children, tint, lanceCounter, remaining));
            }
        }
        if (master) {
            int free = Math.min(entity.calculateFreeC3Nodes(), remaining);
            if (free > 0) {
                nodes.add(free(entity.getId(), false, free, tint));
            }
        }
        for (Entity child : under) {
            if (child.getOperableC3MCount() > 0) {
                nodes.add(subtree(child, children, LANCE_TINTS[lanceCounter[0]++ % LANCE_TINTS.length],
                      lanceCounter, remaining));
            }
        }
        return member(entity, master ? "roleLanceMaster" : "roleSlave", tint, remaining, nodes);
    }

    /** A member's node: its role (with the computer count of a multi-master), name, id and free capacity. */
    private Node member(Entity entity, String roleKey, Tint tint, int remaining, List<Node> children) {
        String role = text(roleKey);
        int masterCount = entity.getOperableC3MCount();
        if (!entity.hasNhC3() && masterCount > 1) {
            role = role + " x" + masterCount;
        }
        String capacity = "";
        if (entity.isC3CompanyCommander()) {
            capacity = " " + text("capacity", Math.min(entity.calculateFreeC3Nodes(), remaining),
                  Math.min(entity.calculateFreeC3MNodes(), remaining));
        } else if (!entity.hasNhC3() && masterCount > 0) {
            capacity = " " + text("capacitySlaves", Math.min(entity.calculateFreeC3Nodes(), remaining));
        }
        return new Node(NodeKind.UNIT, entity.getId(), role, entity.getShortNameRaw(), "#" + entity.getId() + capacity,
              "", tint, new Target.Unit(entity.getId()), List.copyOf(children));
    }

    private Node free(int masterId, boolean masters, int count, Tint tint) {
        return new Node(NodeKind.FREE, -1, text(masters ? "roleLanceMaster" : "roleSlave"), text("freeSlots", count),
              "", text(masters ? "slotMasters" : "slotSlaves", count), tint, new Target.Unit(masterId), List.of());
    }

    // ---- Roster ----

    private void group(List<RosterGroup> roster, String key, List<Entity> units) {
        if (units.isEmpty()) {
            return;
        }
        List<RosterUnit> rows = new ArrayList<>();
        for (Entity unit : units) {
            String equipment = equipment(unit);
            rows.add(new RosterUnit(unit.getId(), unit.getShortNameRaw(), equipment,
                  text("rosterLabel", unit.getShortNameRaw(), unit.getId(), equipment), planned(unit.getId())));
        }
        roster.add(new RosterGroup(text(key), List.copyOf(rows)));
    }

    /** A short description of the unit's C3 gear, such as "Master x3" or "C3i". */
    static String equipment(Entity entity) {
        if (entity.hasC3i()) {
            return "C3i";
        }
        if (entity.hasNavalC3()) {
            return "NC3";
        }
        if (entity.hasNovaCEWS()) {
            return "Nova CEWS";
        }
        int masters = entity.getOperableC3MCount();
        if (masters > 1) {
            return text("equipMasters", masters);
        }
        return text(masters == 1 ? "equipMaster" : "equipSlave");
    }

    /** How far the unassigned force fills each configuration template, as its button's tooltip. */
    private static List<String> templateTips(List<Entity> masters, List<Entity> slaves) {
        List<String> tips = new ArrayList<>();
        for (int number = 1; number <= 4; number++) {
            int needed = number;
            boolean headable = masters.stream().anyMatch(master -> master.getOperableC3MCount() >= needed);
            if (!headable) {
                tips.add(text("configNoCompany", number));
                continue;
            }
            int mastersShort = Math.max(0, LANCE_MASTERS_NEEDED[number - 1] - (masters.size() - 1));
            int slavesShort = Math.max(0, SLAVES_NEEDED[number - 1] - slaves.size());
            tips.add((mastersShort == 0 && slavesShort == 0)
                  ? text("configReady", number, 1 + LANCE_MASTERS_NEEDED[number - 1] + SLAVES_NEEDED[number - 1])
                  : text("configShort", number, mastersShort, slavesShort));
        }
        return List.copyOf(tips);
    }

    private static String forcePlanText(ForcePlan plan) {
        if (plan.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (TemplateAssignment company : plan.companies()) {
            parts.add(text("planPartConfig", company.configurationNumber(), company.filledCount()));
        }
        for (PlannedLance lance : plan.lances()) {
            parts.add(text(lance.allMasters() ? "planPartAllMasterLance" : "planPartLance",
                  1 + lance.members().size()));
        }
        return text("forcePlan", String.join(" + ", parts), plan.leftoverUnits());
    }

    // ---- Helpers ----

    private record Reset(List<Entity> disconnect, List<Entity> lone) {}

    private Reset readReset() {
        List<Entity> disconnect = new ArrayList<>();
        List<Entity> lone = new ArrayList<>();
        for (Entity entity : game().inGameTWEntities()) {
            if (entity.getOwnerId() != localPlayerId || !entity.hasAnyC3System()) {
                continue;
            }
            if (!isNetworked(entity)) {
                if (entity.isC3CompanyCommander()) {
                    lone.add(entity);
                }
                continue;
            }
            String netId = entity.getC3NetId();
            boolean peerName = entity.hasNhC3() && netId != null
                  && netId.endsWith(Entity.C3_NETWORK_ID_SEPARATOR + entity.getId());
            if (!peerName) {
                disconnect.add(entity);
            }
        }
        return new Reset(disconnect, lone);
    }

    private Game game() {
        return game.get();
    }

    private C3FormationPlanner planner() {
        return new C3FormationPlanner(game(), localPlayerId);
    }

    private boolean isNetworked(Entity entity) {
        return game().getC3NetworkMembers(entity).size() > 1;
    }

    private List<Entity> entities(Collection<Integer> ids) {
        List<Entity> units = new ArrayList<>();
        for (int id : new LinkedHashSet<>(ids)) {
            Entity entity = game().getEntity(id);
            if (entity != null) {
                units.add(entity);
            }
        }
        return units;
    }

    private List<Entity> resolve(Integer[] slots) {
        List<Entity> units = new ArrayList<>();
        for (Integer id : slots) {
            Entity entity = (id == null) ? null : game().getEntity(id);
            if (entity != null) {
                units.add(entity);
            }
        }
        return units;
    }

    private static List<Entity> sorted(List<Entity> entities) {
        List<Entity> sorted = new ArrayList<>(entities);
        sorted.sort(Comparator.comparingInt(Entity::getId));
        return sorted;
    }

    private static String text(String key, Object... arguments) {
        return (arguments.length == 0) ? Messages.getString(KEY + key) : Messages.getString(KEY + key, arguments);
    }
}
