/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Vector;
import java.util.function.Consumer;

import megamek.client.AbstractClient;
import megamek.client.Client;
import megamek.common.Player;
import megamek.common.enums.GamePhase;
import megamek.common.force.Force;
import megamek.common.game.Game;
import megamek.common.icons.Camouflage;
import megamek.common.preference.ClientPreferences;
import megamek.common.preference.PreferenceManager;
import megamek.common.units.Crew;
import megamek.common.units.CrewType;
import megamek.common.units.Entity;
import megamek.common.util.C3Util;
import megamek.common.util.C3Util.C3CapacityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

class LobbyCommandsTest {

    private static final int TEAM_LOCAL = 1;
    private static final int TEAM_BOT = 2;
    private static final int TEAM_ENEMY = 3;

    private Game game;
    private Client client;
    private Client botClient;
    private Player local;
    private Player bot;
    private Player ally;
    private Player enemy;
    private LobbyPrompts prompts;
    private LobbyCommands commands;
    private int nextEntityId = 1;

    @BeforeEach
    void setUp() {
        game = new Game();
        local = player(1, "Local", TEAM_LOCAL);
        bot = player(2, "Bot", TEAM_BOT);
        ally = player(3, "Ally", TEAM_LOCAL);
        enemy = player(4, "Enemy", TEAM_ENEMY);
        bot.setBot(true);

        client = mock(Client.class);
        botClient = mock(Client.class);
        Map<String, AbstractClient> bots = new HashMap<>(Map.of(bot.getName(), botClient));
        when(client.getGame()).thenReturn(game);
        when(client.getLocalPlayer()).thenReturn(local);
        when(client.getBots()).thenReturn(bots);
        when(botClient.getLocalPlayer()).thenReturn(bot);

        prompts = mock(LobbyPrompts.class);
        commands = new LobbyCommands(() -> client, prompts);
    }

    private Player player(int id, String name, int team) {
        Player player = new Player(id, name);
        player.setTeam(team);
        game.addPlayer(id, player);
        return player;
    }

    private Entity unitOf(Player owner) {
        Entity entity = mock(Entity.class);
        when(entity.getId()).thenReturn(nextEntityId++);
        when(entity.getOwner()).thenReturn(owner);
        when(entity.getOwnerId()).thenReturn(owner.getId());
        when(entity.getForceId()).thenReturn(Force.NO_FORCE);
        return entity;
    }

    @Test
    void ownUnitIsSentByTheLocalClient() {
        assertSame(client, commands.correctSender(unitOf(local)));
    }

    @Test
    void aSaveSendsTheLocalSaveCommandWithSpacesKeptApart() {
        File file = new File(new File("My Games", "camp aign"), "Battle of Tukayyid.sav.gz");
        String folder = file.getAbsoluteFile().getParent().replace(' ', '|');
        assertEquals("/localsave Battle_of_Tukayyid.sav.gz " + folder, LobbyCommands.localSaveCommand(file));

        Consumer<File> saved = ignored -> { };
        commands.saveGame(file, saved);
        verify(client).setSaveCompletionCallback(saved);
        verify(client).setAwaitingSave(true);
        verify(client).sendChat("/localsave Battle_of_Tukayyid.sav.gz " + folder);
    }

    @Test
    void aQuickSaveSavesToTheQuickSaveFile() {
        Consumer<File> saved = ignored -> { };
        commands.quickSave(saved);
        verify(client).setSaveCompletionCallback(saved);
        verify(client).sendChat("/localsave quicksave ./savegames");
    }

    @Test
    void pingingAnotherPlayerPingsThemAlone() {
        commands.ping(enemy.getId());
        verify(client).sendChat("/ping Enemy");
    }

    @Test
    void pingingOneselfPingsEveryone() {
        commands.ping(local.getId());
        verify(client).sendChat("/ping");
    }

    @Test
    void aDisconnectedPlayerIsNotPinged() {
        enemy.setGhost(true);
        commands.ping(enemy.getId());
        verify(client, never()).sendChat(anyString());
    }

    @Test
    void localBotUnitIsSentByTheBotClient() {
        assertSame(botClient, commands.correctSender(unitOf(bot)));
    }

    @Test
    void allyUnitIsSentByTheLocalClient() {
        assertSame(client, commands.correctSender(unitOf(ally)));
    }

    @Test
    void enemyUnitHasNoSenderWithoutGameMaster() {
        assertNull(commands.correctSender(unitOf(enemy)));
    }

    @Test
    void enemyUnitIsSentByTheBotOnItsTeam() {
        Player botTeammate = player(5, "Teammate", TEAM_BOT);
        assertSame(botClient, commands.correctSender(unitOf(botTeammate)));
    }

    @Test
    void enemyUnitIsSentByTheLocalClientForGameMaster() {
        local.setGameMaster(true);
        assertSame(client, commands.correctSender(unitOf(enemy)));
    }

    @Test
    void updatesAreSplitBetweenLocalClientAndBot() {
        Entity own = unitOf(local);
        Entity botUnit = unitOf(bot);
        Entity enemyUnit = unitOf(enemy);

        commands.sendUpdates(List.of(own, botUnit, enemyUnit));

        verify(client).sendUpdateEntity(List.<Entity>of(own));
        verify(botClient).sendUpdateEntity(List.<Entity>of(botUnit));
    }

    @Test
    void configuringAnEnemyUnitIsRefused() {
        Entity enemyUnit = unitOf(enemy);

        commands.applyDeployment(List.of(enemyUnit), 3);

        verify(prompts).error(LobbyError.CANNOT_CONFIG_ENEMIES);
        verify(enemyUnit, never()).setDeployRound(anyInt());
        verify(client, never()).sendUpdateEntity(anyCollection());
    }

@Test
    void aCamoPickerAnswersLaterAndTheCamoIsSentThen() {
        Entity own = unitOf(local);
        when(own.getCamouflage()).thenReturn(new Camouflage());
        when(own.getCamouflageOrElseOwners()).thenReturn(local.getCamouflage());
        List<Consumer<Camouflage>> pending = new ArrayList<>();
        commands.camoPicker((initial, individual, units, chosen) -> pending.add(chosen));

        commands.individualCamo(List.of(own));

        verify(prompts, never()).chooseCamo(any(), anyBoolean(), any());
        verify(client, never()).sendUpdateEntity(anyCollection());
        Camouflage red = new Camouflage(Camouflage.COLOUR_CAMOUFLAGE, "RED");
        pending.get(0).accept(red);
        verify(own).setCamouflage(red);
        verify(client).sendUpdateEntity(List.<Entity>of(own));
    }

    @Test
    void exceedingC3CapacityIsRefused() {        Entity own = unitOf(local);
        try (MockedStatic<C3Util> c3Util = mockStatic(C3Util.class)) {
            c3Util.when(() -> C3Util.connect(any(), anyCollection(), anyInt(), anyBoolean()))
                  .thenThrow(new C3CapacityException());

            commands.c3Connect(List.of(own), 99, false);
        }

        verify(prompts).error(LobbyError.EXCEED_C3_CAPACITY);
        verify(client, never()).sendUpdateEntity(anyCollection());
    }

    @Test
    void deleteDoesNotSendWhenRefused() {
        Entity own = unitOf(local);
        when(prompts.confirm(anyString(), anyString())).thenReturn(false);

        commands.delete(new ArrayList<>(), new ArrayList<>(List.of(own)), true);

        verify(prompts).confirm("Delete Units...", "Really delete one unit?");
        verify(client, never()).sendDeleteEntities(anyList());
    }

    @Test
    void deleteSendsWhenConfirmed() {
        Entity own = unitOf(local);
        int ownId = own.getId();
        when(prompts.confirm(anyString(), anyString())).thenReturn(true);

        commands.delete(new ArrayList<>(), new ArrayList<>(List.of(own)), true);

        verify(client).sendDeleteEntities(List.of(ownId));
    }

    @Test
    void forceIsRenamedThroughThePrompt() {
        Force force = Force.createToplevelForce("Alpha", local);
        int forceId = game.getForces().addTopLevelForce(force, local);
        when(prompts.chooseForceName(any())).thenReturn(Optional.of("Bravo Lance"));

        commands.forceRename(forceId);

        ArgumentCaptor<ForceNameRequest> request = ArgumentCaptor.forClass(ForceNameRequest.class);
        verify(prompts).chooseForceName(request.capture());
        assertEquals("Alpha", request.getValue().currentName());
        assertEquals(local, request.getValue().owner());
        assertEquals("Bravo Lance", game.getForces().getForce(forceId).getName());
        verify(client).sendUpdateForce(List.of(game.getForces().getForce(forceId)));
    }

    @Test
    void cancelledForceRenameSendsNothing() {
        Force force = Force.createToplevelForce("Alpha", local);
        int forceId = game.getForces().addTopLevelForce(force, local);
        when(prompts.chooseForceName(any())).thenReturn(Optional.empty());

        commands.forceRename(forceId);

        assertEquals("Alpha", game.getForces().getForce(forceId).getName());
        verify(client, never()).sendUpdateForce(anyCollection());
    }

    @Test
    void renamingAnEnemyForceIsRefused() {
        Force force = Force.createToplevelForce("Alpha", enemy);
        int forceId = game.getForces().addTopLevelForce(force, enemy);

        commands.forceRename(forceId);

        verify(prompts).error(LobbyError.CANNOT_CONFIG_ENEMIES);
        verify(prompts, never()).chooseForceName(any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void changeTeamSendsOnlyLocalPlayersThatChange() {
        commands.changeTeam(List.of(local, bot, enemy), TEAM_BOT);

        ArgumentCaptor<Vector<Player>> sent = ArgumentCaptor.forClass(Vector.class);
        verify(client).sendChangeTeam(sent.capture(), eq(TEAM_BOT));
        assertEquals(List.of(local), sent.getValue());
    }

    @Test
    void removingAPlayerThatIsNoLocalBotIsRefused() {
        commands.removeBot(client);

        verify(prompts).error(LobbyError.ONLY_OWN_BOT);
        verify(client, never()).die();
    }

    @Test
    void removingALocalBotStopsIt() {
        when(botClient.getName()).thenReturn(bot.getName());
        commands.removeBot(botClient);

        verify(botClient).die();
        assertFalse(client.getBots().containsKey(bot.getName()));
    }

    @Test
    void readyIsAllowedWithoutBlockers() {
        assertTrue(commands.checkReady());
        verify(prompts, never()).readyBlocked(any());
    }

    @Test
    void doneIsSentForLocalPlayerAndBots() {
        commands.sendDone(true);

        verify(client).sendDone(true);
        verify(botClient).sendDone(true);
    }

    @Test
    void scanTargetLogsAndSendsThroughTheBot() {
        Entity botUnit = unitOf(bot);

        commands.applyScanTarget(List.of(botUnit), true);

        verify(botUnit).setDesignatedScanTarget(true);
        verify(botClient).sendUpdateEntity(List.<Entity>of(botUnit));
    }

    @Test
    void directEditingExcludesAlliesUnlessGameMaster() {
        Entity allyUnit = unitOf(ally);
        assertFalse(commands.isDirectlyEditable(allyUnit));
        assertTrue(commands.isDirectlyEditable(unitOf(bot)));
        local.setGameMaster(true);
        assertTrue(commands.isDirectlyEditable(allyUnit));
    }

    @Test
    void proxyUpdatesUseTheBotClientForBots() {
        Collection<Entity> units = List.of(unitOf(bot));

        commands.sendProxyUpdates(units, bot);

        verify(botClient).sendUpdateEntity(units);
    }

    @Test
    void aNewBotJoinsItsTeamOnceItHasItsPlayer() {
        Client newBot = mock(Client.class);

        assertFalse(commands.joinTeam(newBot, TEAM_ENEMY));
        verify(newBot, never()).sendPlayerInfo();

        Player joined = new Player(6, "New Bot");
        joined.setTeam(TEAM_LOCAL);
        when(newBot.getLocalPlayer()).thenReturn(joined);

        assertTrue(commands.joinTeam(newBot, TEAM_ENEMY));
        assertEquals(TEAM_ENEMY, joined.getTeam());
        verify(newBot).sendPlayerInfo();
    }

    @Test
    void aNewBotAlreadyOnItsTeamSendsNothing() {
        Client newBot = mock(Client.class);
        Player joined = new Player(6, "New Bot");
        joined.setTeam(TEAM_ENEMY);
        when(newBot.getLocalPlayer()).thenReturn(joined);

        assertTrue(commands.joinTeam(newBot, TEAM_ENEMY));
        assertTrue(commands.joinTeam(newBot, Player.TEAM_UNASSIGNED));
        verify(newBot, never()).sendPlayerInfo();
    }

    @Test
    void findsTheClientsOfLocalBotsOnly() {
        assertSame(botClient, commands.localBotClient(bot));
        assertNull(commands.localBotClient(local));
        assertNull(commands.localBotClient(enemy));
    }

    // ---- Requisition ----

    private Entity crewedUnit() {
        Entity entity = mock(Entity.class);
        when(entity.getCrew()).thenReturn(new Crew(CrewType.SINGLE));
        return entity;
    }

    private void withoutGeneratedNames(Runnable test) {
        ClientPreferences preferences = PreferenceManager.getClientPreferences();
        boolean names = preferences.generateNames();
        preferences.setGenerateNames(false);
        try {
            test.run();
        } finally {
            preferences.setGenerateNames(names);
        }
    }

    @Test
    void addedUnitsGoToTheServerForTheChosenBot() {
        withoutGeneratedNames(() -> {
            Entity first = crewedUnit();
            Entity second = crewedUnit();

            Player owner = commands.addUnits(List.of(first, second), bot,
                  new RequisitionSkills(RequisitionSkills.Mode.FIXED, 3, 4));

            assertSame(bot, owner);
            verify(first).setOwner(bot);
            verify(second).setOwner(bot);
            assertEquals(3, first.getCrew().getGunnery());
            assertEquals(3, first.getCrew().getGunneryL());
            assertEquals(3, first.getCrew().getGunneryM());
            assertEquals(3, first.getCrew().getGunneryB());
            assertEquals(4, first.getCrew().getPiloting());
            verify(client).sendAddEntity(List.of(first, second));
            verify(client).sendServerChat(eq(Player.PLAYER_NONE), eq(local + " selected 2 units for player: Bot"));
        });
    }

    @Test
    void addedUnitsKeepTheirFileSkillsByDefault() {
        withoutGeneratedNames(() -> {
            Entity unit = crewedUnit();
            unit.getCrew().setGunnery(2, 0);

            assertSame(local, commands.addUnits(List.of(unit), null, RequisitionSkills.initial(false)));
            assertEquals(2, unit.getCrew().getGunnery());
            verify(client).sendServerChat(eq(Player.PLAYER_NONE), eq(local + " selected a unit for player: Local"));
        });
    }

    @Test
    void nothingToAddSendsNothing() {
        assertNull(commands.addUnits(List.of(), bot, RequisitionSkills.initial(false)));
        verify(client, never()).sendAddEntity(anyList());
    }

    @Test
    void unitsAddedInTheLoungeKeepTheirDeployment() {
        withoutGeneratedNames(() -> {
            game.setPhase(GamePhase.LOUNGE);
            Entity unit = crewedUnit();

            commands.addUnits(List.of(unit), null, RequisitionSkills.initial(false));

            verify(unit).setOwner(local);
            verify(unit, never()).setDeployRound(anyInt());
        });
    }

    @Test
    void unitsAddedDuringTheGameArriveAsReinforcementsNextRound() {
        withoutGeneratedNames(() -> {
            game.setPhase(GamePhase.MOVEMENT);
            game.setRoundCount(3);
            Entity unit = crewedUnit();

            commands.addUnits(List.of(unit), bot, RequisitionSkills.initial(false));

            verify(unit).setOwner(bot);
            verify(unit).setDeployRound(4);
            verify(unit).setDone(true);
            verify(unit).setUnloaded(true);
            verify(client).sendAddEntity(List.of(unit));
        });
    }

    @Test
    void unitsForAPlayerTheyMayNotGoToGoToTheLocalPlayer() {
        assertSame(bot, commands.recipient(bot));
        assertSame(local, commands.recipient(null));
        assertSame(local, commands.recipient(enemy));
        assertSame(local, commands.recipient(new Player(9, "Gone")));
    }
}
