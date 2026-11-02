/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;

import megamek.client.lobby.C3NetworkBoard.Network;
import megamek.client.lobby.C3NetworkBoard.Node;
import megamek.client.lobby.C3NetworkBoard.NodeKind;
import megamek.client.lobby.C3NetworkBoard.PeerSystem;
import megamek.client.lobby.C3NetworkBoard.Picture;
import megamek.client.lobby.C3NetworkBoard.PlanCard;
import megamek.client.lobby.C3NetworkBoard.SlotKind;
import megamek.client.lobby.C3NetworkBoard.Target;
import megamek.client.lobby.C3NetworkBoard.Tint;
import megamek.common.Player;
import megamek.common.equipment.EquipmentType;
import megamek.common.force.Force;
import megamek.common.force.Forces;
import megamek.common.game.Game;
import megamek.common.units.BipedMek;
import megamek.common.units.Crew;
import megamek.common.units.CrewType;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import megamek.common.util.C3Util;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The C3 manager's board on real units: its roster, plan cards, builds, trees, drops and resets. */
class C3NetworkBoardTest {
    private static final int[] MASTER_LOCATIONS =
          { Mek.LOC_HEAD, Mek.LOC_CENTER_TORSO, Mek.LOC_LEFT_TORSO, Mek.LOC_RIGHT_TORSO };

    private Game game;
    private Player player;
    private int nextId;
    private final List<String> calls = new ArrayList<>();

    @BeforeAll
    static void equipment() {
        EquipmentType.initializeTypes();
    }

    @BeforeEach
    void setUp() {
        game = new Game();
        player = new Player(0, "Test Player");
        game.addPlayer(0, player);
        nextId = 1;
        calls.clear();
    }

    @Test
    void theRosterGroupsTheUnassignedUnitsBySystemAndTheTemplatesSayWhatTheyLack() {
        Entity master = master(1);
        slaves(3);
        C3NetworkBoard board = board(List.of(master.getId()));

        Picture picture = board.picture();
        assertEquals(2, picture.roster().size(), "masters, then slaves");
        assertEquals(1, picture.roster().get(0).units().size());
        assertEquals(3, picture.roster().get(1).units().size());
        assertEquals("Master", picture.roster().get(0).units().get(0).equipment());
        assertNotNull(picture.startHint(), "nothing on the network side: the start hint shows");
        assertEquals(List.of(master.getId()), picture.selection(), "the unit it opened on is selected");
        assertTrue(board.picture().selection().isEmpty(), "once");
        assertTrue(picture.templateTips().get(0).contains("short"), picture.templateTips().get(0));
        assertTrue(picture.templateTips().get(1).contains("none is unassigned"), "a single master heads no Config 2");
        assertTrue(picture.planForceEnabled(), "a master and three slaves make a lance");
        assertFalse(picture.buildVisible());
        assertFalse(picture.peerVisible());
    }

    @Test
    void aConfiguration2TemplateFillsFromTheLancesAndBuildsTheDiagramsTree() {
        int company = company();
        Entity head = master(2);
        lance(company, head, slaves(3));
        Entity alpha = master(1);
        lance(company, alpha, slaves(3));
        Entity bravo = master(1);
        lance(company, bravo, slaves(3));
        C3NetworkBoard board = board(List.of());

        board.startTemplate(2);
        Picture planned = board.picture();
        assertEquals(2, planned.templateLit());
        assertEquals(1, planned.plans().size());
        PlanCard card = planned.plans().get(0);
        assertEquals(12, card.filled());
        assertEquals(head.getId(), card.root().unitId(), "the head on top of the card");
        assertEquals(5, card.root().children().size(), "three head slaves and two lance masters");
        assertEquals(Tint.COMPANY_LANCE, card.root().children().get(0).tint(), "the head's own lance");
        assertTrue(planned.rosterUnits().stream().allMatch(C3NetworkBoard.RosterUnit::planned));
        assertEquals(12, planned.planFilled(), "a new template asks before replacing these");

        board.build();
        Picture built = board.picture();
        assertTrue(built.plans().isEmpty(), "the cards go once built");
        assertTrue(built.roster().isEmpty());
        assertEquals(1, built.networks().size());
        Network network = built.networks().get(0);
        assertEquals(12, network.members().size());
        assertEquals(head.getId(), network.topId());
        assertTrue(network.configComplete(), network.config());
        Node root = network.roots().get(0);
        assertEquals(1, network.roots().size());
        assertEquals(Tint.COMPANY, root.tint());
        assertEquals(head.getId(), root.unitId());
        List<Node> lanceMasters = root.children().stream().filter(node -> !node.children().isEmpty()).toList();
        assertEquals(2, lanceMasters.size(), "two lance masters under the company");
        assertEquals(3, lanceMasters.get(0).children().size(), "each runs its three slaves");
        assertTrue(built.disconnectVisible());
    }

    @Test
    void withoutAutoAssignATemplateLaysOutEmptySlotsThatTakeOnlyUnitsThatFit() {
        Entity head = master(1);
        List<Entity> slaves = slaves(2);
        C3NetworkBoard board = board(List.of());
        board.autoAssign(false);
        board.startTemplate(1);
        Picture empty = board.picture();
        assertEquals(0, empty.planFilled());
        assertTrue(empty.autoAssignVisible(), "Auto-assign offers itself while the plan is empty");
        Node company = empty.plans().get(0).root();
        assertEquals(NodeKind.SLOT, company.kind());
        assertEquals(-1, company.unitId());

        board.drop(List.of(slaves.get(0).getId()), company.target());
        assertTrue(board.picture().status().contains("does not fit"), "a slave cannot head the company");
        assertTrue(board.picture().notice());

        board.drop(List.of(head.getId(), slaves.get(0).getId(), slaves.get(1).getId()), company.target());
        Picture filled = board.picture();
        assertEquals(3, filled.planFilled(), "the head takes the slot, the slaves the first open ones");
        assertFalse(filled.notice());

        board.drop(List.of(slaves.get(1).getId()), new Target.Roster());
        assertEquals(2, board.picture().planFilled(), "the tray takes a unit back out of its slot");

        board.build();
        assertEquals(List.of("companyMaster " + head.getId()), calls, "a lance slave with no lance master is left out");
        assertTrue(board.picture().notice());
        assertEquals(1, board.picture().networks().size());
    }

    @Test
    void planForceLaysOutCardsAndDiscardThrowsThemAway() {
        Entity master = master(1);
        slaves(3);
        C3NetworkBoard board = board(List.of());
        assertTrue(board.picture().forcePlan().contains("lance of 4"), board.picture().forcePlan());
        board.planForce();
        Picture picture = board.picture();
        assertTrue(picture.planForceLit());
        assertEquals(0, picture.templateLit());
        assertEquals(1, picture.plans().size());
        assertEquals(master.getId(), picture.plans().get(0).root().unitId());
        assertEquals(4, picture.planFilled());
        board.discardPlan();
        assertTrue(board.picture().plans().isEmpty());
        assertTrue(calls.isEmpty(), "plans change nothing in the game");
    }

    @Test
    void droppingUnitsOnAMasterConnectsThemAndTheTrayDisconnectsThem() {
        Entity master = master(1);
        List<Entity> slaves = slaves(2);
        C3NetworkBoard board = board(List.of());
        board.drop(ids(slaves), new Target.RosterUnit(master.getId()));
        Picture picture = board.picture();
        assertEquals(1, picture.networks().size());
        Node root = picture.networks().get(0).roots().get(0);
        assertEquals(master.getId(), root.unitId());
        assertEquals(2, root.children().stream().filter(node -> node.kind() == NodeKind.UNIT).count());
        assertEquals(1, root.children().stream().filter(node -> node.kind() == NodeKind.FREE).count(),
              "a free link left");

        board.drop(List.of(slaves.get(0).getId()), new Target.Roster());
        assertEquals(1, board.picture().networks().get(0).roots().get(0).children().stream()
              .filter(node -> node.kind() == NodeKind.UNIT).count());
        board.disconnect(List.of());
        assertTrue(board.picture().status().contains("Select units"));
    }

    @Test
    void aMasterDroppedOnTheNetworkSideSeedsANetworkAndASlaveGetsAHint() {
        Entity master = master(1);
        List<Entity> slaves = slaves(1);
        C3NetworkBoard board = board(List.of());
        board.drop(ids(slaves), new Target.NewNetwork());
        assertTrue(board.picture().notice());
        board.drop(List.of(master.getId()), new Target.NewNetwork());
        Picture picture = board.picture();
        assertEquals(1, picture.networks().size());
        assertTrue(picture.networks().get(0).seed());
        assertTrue(picture.discardVisible());
        assertNull(picture.startHint());
        board.drop(ids(slaves), new Target.Unit(master.getId()));
        assertFalse(board.picture().networks().get(0).seed(), "the seed grows into a network");
    }

    @Test
    void peerUnitsFormANetworkThroughTheirSeedAndResetClearsEverything() {
        List<Entity> peers = new ArrayList<>();
        for (int count = 0; count < 3; count++) {
            peers.add(unit("C3i", "ISC3iUnit", Mek.LOC_HEAD));
        }
        C3NetworkBoard board = board(List.of());
        Picture before = board.picture();
        assertTrue(before.peerVisible());
        assertEquals(PeerSystem.C3I, before.peerSeeds().get(0).system());
        assertFalse(board.resettable());

        board.drop(ids(peers), new Target.Peer(PeerSystem.C3I));
        Picture after = board.picture();
        assertEquals(1, after.networks().size());
        assertTrue(after.networks().get(0).peer());
        assertTrue(after.networks().get(0).roots().stream().anyMatch(node -> node.kind() == NodeKind.FREE));
        assertTrue(board.resettable());
        board.reset();
        assertTrue(board.picture().networks().isEmpty(), "reset disconnects the peers");
        board.reset();
        assertTrue(board.picture().status().contains("No networks"));
    }

    @Test
    void aSlotDropOnAPlanCardTargetsItsSlot() {
        master(1);
        Entity slave = slaves(1).get(0);
        C3NetworkBoard board = board(List.of());
        board.autoAssign(false);
        board.startTemplate(4);
        board.drop(List.of(slave.getId()), new Target.Slot(0, SlotKind.DIRECT_SLAVE, -1, 4));
        Node slot = board.picture().plans().get(0).root().children().get(4);
        assertEquals(slave.getId(), slot.unitId());
        board.drop(List.of(slave.getId()), new Target.Plan(0));
        assertEquals(slave.getId(), board.picture().plans().get(0).root().children().get(0).unitId(),
              "a drop on the card fills the first open slot");
    }

    // ---- Helpers ----

    private C3NetworkBoard board(List<Integer> selection) {
        return new C3NetworkBoard(() -> game, player.getId(), new GameCommands(), selection);
    }

    private static List<Integer> ids(List<Entity> units) {
        return units.stream().map(Entity::getId).toList();
    }

    /** Applies the C3 actions to the game as the lobby's commands do, and records them. */
    private final class GameCommands implements C3NetworkBoard.Commands {
        @Override
        public void disconnect(List<Entity> units) {
            calls.add("disconnect " + ids(units));
            C3Util.disconnectFromNetwork(game, units);
        }

        @Override
        public void companyMaster(List<Entity> units) {
            calls.add("companyMaster " + units.get(0).getId());
            try {
                C3Util.setCompanyMaster(units);
            } catch (Exception failure) {
                fail(failure);
            }
        }

        @Override
        public void lanceMaster(List<Entity> units) {
            calls.add("lanceMaster " + ids(units));
            try {
                C3Util.setLanceMaster(units);
            } catch (Exception failure) {
                fail(failure);
            }
        }

        @Override
        public void joinPeers(List<Entity> units, int masterId, boolean disconnectFirst) {
            calls.add("join " + ids(units) + " to " + masterId);
            try {
                C3Util.joinNh(game, units, masterId, disconnectFirst);
            } catch (Exception failure) {
                fail(failure);
            }
        }

        @Override
        public void connect(List<Entity> units, int masterId, boolean disconnectFirst) {
            calls.add("connect " + ids(units).stream().map(String::valueOf).reduce((a, b) -> a + "," + b)
                  .orElse("") + " to " + masterId);
            try {
                C3Util.connect(game, units, masterId, disconnectFirst);
            } catch (Exception failure) {
                fail(failure);
            }
        }
    }

    private int company() {
        return game.getForces().addTopLevelForce(Force.createToplevelForce("Company", player), player);
    }

    private void lance(int companyId, Entity master, List<Entity> members) {
        Forces forces = game.getForces();
        Force company = forces.getForce(companyId);
        int lanceId = forces.addSubForce(Force.createSubforce("Lance " + master.getId(), company), company);
        forces.addEntity(master, lanceId);
        members.forEach(member -> forces.addEntity(member, lanceId));
    }

    private Entity master(int computers) {
        Entity entity = base("C3M x" + computers);
        try {
            for (int index = 0; index < computers; index++) {
                entity.addEquipment(EquipmentType.get("ISC3MasterComputer"), MASTER_LOCATIONS[index]);
            }
        } catch (Exception failure) {
            fail(failure);
        }
        game.addEntity(entity);
        return entity;
    }

    private List<Entity> slaves(int count) {
        List<Entity> slaves = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            slaves.add(unit("C3S", "ISC3SlaveUnit", Mek.LOC_HEAD));
        }
        return slaves;
    }

    private Entity unit(String model, String equipment, int location) {
        Entity entity = base(model);
        try {
            entity.addEquipment(EquipmentType.get(equipment), location);
        } catch (Exception failure) {
            fail(failure);
        }
        game.addEntity(entity);
        return entity;
    }

    private Entity base(String model) {
        Entity entity = new BipedMek();
        entity.setGame(game);
        entity.setId(nextId++);
        entity.setChassis("Test Mek");
        entity.setModel(model);
        entity.setCrew(new Crew(CrewType.SINGLE));
        entity.setOwner(player);
        entity.setWeight(50.0);
        entity.setOriginalWalkMP(5);
        return entity;
    }
}
