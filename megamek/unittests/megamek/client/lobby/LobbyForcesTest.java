/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import megamek.client.AbstractClient;
import megamek.client.Client;
import megamek.client.lobby.LobbyForces.Formation;
import megamek.client.lobby.LobbyForces.Owner;
import megamek.client.lobby.LobbyForces.Row;
import megamek.client.lobby.LobbyUnit.Tone;
import megamek.client.ui.Messages;
import megamek.common.Player;
import megamek.common.equipment.EquipmentType;
import megamek.common.force.Force;
import megamek.common.game.Game;
import megamek.common.options.OptionsConstants;
import megamek.common.units.BipedMek;
import megamek.common.units.Entity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LobbyForcesTest {

    private Game game;
    private Client client;
    private Player local;
    private Player ally;
    private Player enemy;
    private final Map<String, AbstractClient> bots = new HashMap<>();

    @BeforeAll
    static void loadEquipment() {
        EquipmentType.initializeTypes();
    }

    @BeforeEach
    void setUp() {
        game = new Game();
        local = player(1, "Local", 1);
        ally = player(2, "Ally", 1);
        enemy = player(3, "Enemy", 2);
        client = mock(Client.class);
        when(client.getGame()).thenReturn(game);
        when(client.getLocalPlayer()).thenReturn(local);
        when(client.getBots()).thenReturn(bots);
    }

    private Player player(int id, String name, int team) {
        Player player = new Player(id, name);
        player.setTeam(team);
        game.addPlayer(id, player);
        return player;
    }

    private Entity mek(Player owner, String chassis, double tons) {
        BipedMek mek = new BipedMek();
        mek.setChassis(chassis);
        mek.setModel("X");
        mek.setWeight(tons);
        mek.setOwner(owner);
        game.addEntity(mek);
        return mek;
    }

    private LobbyForces forces() {
        return LobbyForces.of(() -> client, LobbyForces.Order.NONE);
    }

    private void option(String name, boolean value) {
        game.getOptions().getOption(name).setValue(value);
    }

    @Test
    void ownersListTheLocalPlayerFirstThenAlliesThenEnemies() {
        mek(enemy, "Atlas", 100);
        mek(ally, "Locust", 20);
        mek(local, "Hunchback", 50);
        mek(local, "Wolverine", 55);

        List<Owner> owners = forces().owners();

        assertEquals(List.of("Local", "Ally", "Enemy"), owners.stream().map(Owner::name).toList());
        assertTrue(owners.getFirst().self());
        assertEquals(2, owners.getFirst().unitIds().size());
        assertEquals(105, owners.getFirst().tons());
        assertTrue(owners.get(2).enemy());
    }


    @Test
    void cardShowsNameTonnageAndWeightClass() {
        Entity hunchback = mek(local, "Hunchback", 50);

        LobbyUnit card = forces().unit(hunchback.getId());

        assertNotNull(card);
        assertEquals("Hunchback X", card.name());
        assertEquals(50, card.tons());
        assertEquals(hunchback.getWeightClassName(), card.weightClass());
        assertTrue(card.details().contains(hunchback.getWeightClassName()));
        assertTrue(card.editable());
        assertFalse(card.hidden());
        assertEquals("50 t " + hunchback.getWeightClassName(), card.weight(), "the compact row's weight");
    }

    @Test
    void aHiddenUnitHasNoWeightAndOneWithoutTonnageShowsItsClass() {
        LobbyUnit hidden = new LobbyUnit(1, 1, "Mek", "", "", 0, 0, "", "", "", List.of(), 0, LobbyUnit.Link.NONE,
              true, null, Force.NO_FORCE, false, false, null);
        LobbyUnit classOnly = new LobbyUnit(2, 1, "Asset", "", "Light", 0, 0, "", "", "", List.of(), 0,
              LobbyUnit.Link.NONE, false, null, Force.NO_FORCE, true, false, null);

        assertEquals("", hidden.weight());
        assertEquals("Light", classOnly.weight());
    }

    void blindDropHidesTheEnemysCardButNotOwnOrAllied() {
        option(OptionsConstants.BASE_BLIND_DROP, true);
        Entity atlas = mek(enemy, "Atlas", 100);
        Entity locust = mek(ally, "Locust", 20);

        LobbyForces forces = forces();
        LobbyUnit hidden = forces.unit(atlas.getId());

        assertNotNull(hidden);
        assertTrue(hidden.hidden());
        assertEquals(atlas.getWeightClassName() + Messages.getString("ChatLounge.3"), hidden.name());
        assertEquals("", hidden.bvText());
        assertEquals("", hidden.pilot());
        assertFalse(hidden.editable());
        assertFalse(forces.unit(locust.getId()).hidden());
        Owner enemyOwner = forces.owners().stream().filter(Owner::enemy).findFirst().orElseThrow();
        assertTrue(enemyOwner.hidden());
        assertEquals(0, enemyOwner.bv());
        assertEquals(0, enemyOwner.tons());
    }

    @Test
    void realBlindDropLeavesOutTheEnemysUnitsAndFormations() {
        option(OptionsConstants.BASE_REAL_BLIND_DROP, true);
        Entity atlas = mek(enemy, "Atlas", 100);
        mek(local, "Hunchback", 50);
        game.getForces().addTopLevelForce(Force.createToplevelForce("Red", enemy), enemy);

        LobbyForces forces = forces();

        assertNull(forces.unit(atlas.getId()));
        assertEquals(1, forces.units().size());
        assertTrue(forces.formations().isEmpty());
        assertTrue(forces.tree().stream().noneMatch(row -> row.unitId() == atlas.getId()));
    }

    @Test
    void theGameMasterSeesEverythingInARealBlindDrop() {
        option(OptionsConstants.BASE_REAL_BLIND_DROP, true);
        option(OptionsConstants.BASE_BLIND_DROP, true);
        local.setGameMaster(true);
        Entity atlas = mek(enemy, "Atlas", 100);

        LobbyUnit card = forces().unit(atlas.getId());

        assertNotNull(card);
        assertFalse(card.hidden());
        assertTrue(card.editable());
    }

    @Test
    void treeListsFormationsWithTheirUnitsThenSubFormationsThenLooseUnits() {
        Entity loose = mek(local, "Loose", 20);
        Entity alpha1 = mek(local, "Alpha", 50);
        Entity bravo1 = mek(local, "Bravo", 60);
        Entity enemyUnit = mek(enemy, "Atlas", 100);
        Force alpha = Force.createToplevelForce("Alpha Lance", local);
        int alphaId = game.getForces().addTopLevelForce(alpha, local);
        Force bravo = Force.createSubforce("Bravo Lance", game.getForces().getForce(alphaId));
        int bravoId = game.getForces().addSubForce(bravo, game.getForces().getForce(alphaId));
        game.getForces().addEntity(alpha1, alphaId);
        game.getForces().addEntity(bravo1, bravoId);

        LobbyForces forces = forces();

        assertEquals(List.of(new Row(0, alphaId, -1, Force.NO_FORCE),
              new Row(1, Force.NO_FORCE, alpha1.getId(), alphaId),
              new Row(1, bravoId, -1, alphaId),
              new Row(2, Force.NO_FORCE, bravo1.getId(), bravoId),
              new Row(0, Force.NO_FORCE, loose.getId(), Force.NO_FORCE),
              new Row(0, Force.NO_FORCE, enemyUnit.getId(), Force.NO_FORCE)), forces.tree());
        Formation top = forces.formation(alphaId);
        assertNotNull(top);
        assertEquals(2, top.units());
        assertEquals(110, top.tons());
        assertTrue(top.editable());
        assertEquals(1, forces.formation(bravoId).depth());
        assertEquals(alphaId, forces.unit(alpha1.getId()).forceId());
    }

    @Test
    void anEnemyFormationIsNotEditable() {
        int redId = game.getForces().addTopLevelForce(Force.createToplevelForce("Red", enemy), enemy);

        Formation red = forces().formation(redId);

        assertNotNull(red);
        assertFalse(red.editable());
        assertTrue(red.enemy());
    }

    @Test
    void unitsFollowTheGivenOrder() {
        Entity first = mek(local, "First", 20);
        Entity second = mek(local, "Second", 20);
        Entity third = mek(local, "Third", 20);

        LobbyForces forces = LobbyForces.of(() -> client, new LobbyForces.Order(
              List.of(third.getId(), first.getId()), List.of("By name", "By BV"), 1));

        assertEquals(List.of(third.getId(), first.getId(), second.getId()),
              forces.units().stream().map(LobbyUnit::id).toList());
        assertEquals(List.of(third.getId(), first.getId(), second.getId()), forces.owners().getFirst().unitIds());
        assertEquals(List.of("By name", "By BV"), forces.sorts());
        assertEquals(1, forces.sort());
    }

    @Test
    void offBoardAndDeploymentRoundAreSetupTagsAfterProblems() {
        Entity mek = mek(local, "Hunchback", 50);
        mek.setOffBoard(1, megamek.common.OffBoardDirection.NORTH);
        mek.setDeployRound(3);
        mek.setShutDown(true);

        LobbyUnit card = forces().unit(mek.getId());

        assertNotNull(card);
        assertEquals(Tone.DANGER, card.tags().getFirst().tone());
        assertEquals(Tone.DANGER, card.nameTone());
        assertTrue(card.tags().stream().anyMatch(tag -> tag.text().equals(Messages.getString("Lobby.card.offBoard"))
              && tag.tone() == Tone.SETUP));
        assertTrue(card.tags().stream().anyMatch(tag -> tag.text().equals(Messages.getString("Lobby.card.round", "3"))));
    }

    @Test
    void shownFoldsTheTagsBeyondTheLimit() {
        LobbyUnit card = new LobbyUnit(1, 1, "A", "", "", 0, 0, "", "", "", List.of(
              new LobbyUnit.Tag("a", Tone.FLAVOUR), new LobbyUnit.Tag("b", Tone.DANGER),
              new LobbyUnit.Tag("c", Tone.SETUP), new LobbyUnit.Tag("d", Tone.WARNING)), 7,
              LobbyUnit.Link.C3, false, null, Force.NO_FORCE, true, true, null);

        LobbyUnit.Shown shown = card.shown(LobbyUnit.MAX_TAGS);

        assertEquals(List.of("b", "d", "c"), shown.tags().stream().map(LobbyUnit.Tag::text).toList());
        assertEquals(1, shown.folded());
        assertEquals(LobbyUnit.MAX_DEPTH, card.depth());
    }

    @Test
    void anEmptyGameHasNoForces() {
        LobbyForces forces = forces();

        assertTrue(forces.units().isEmpty());
        assertTrue(forces.tree().isEmpty());
        assertFalse(forces.anyC3());
    }
}
