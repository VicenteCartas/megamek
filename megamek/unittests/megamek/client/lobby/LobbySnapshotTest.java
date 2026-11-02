/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import megamek.client.AbstractClient;
import megamek.client.Client;
import megamek.client.ui.Messages;
import megamek.common.Player;
import megamek.common.game.Game;
import megamek.common.loaders.MapSettings;
import megamek.common.options.GameOptions;
import megamek.common.options.OptionsConstants;
import megamek.common.units.Entity;
import megamek.common.units.FighterSquadron;
import megamek.common.voting.Poll;
import megamek.common.voting.VoteThreshold;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LobbySnapshotTest {

    private Client client;
    private Game game;
    private GameOptions options;
    private Player local;
    private Player bot;
    private Player enemy;
    private final Map<String, AbstractClient> bots = new HashMap<>();

    @BeforeEach
    void setUp() {
        client = mock(Client.class);
        game = mock(Game.class);
        options = mock(GameOptions.class);
        when(client.getGame()).thenReturn(game);
        when(game.getOptions()).thenReturn(options);
        local = spy(new Player(1, "Local"));
        bot = spy(new Player(2, "Bot"));
        enemy = spy(new Player(3, "Enemy"));
        local.setTeam(1);
        bot.setTeam(1);
        bot.setBot(true);
        enemy.setTeam(2);
        doReturn(1200).when(local).getBV();
        doReturn(800).when(bot).getBV();
        doReturn(3000).when(enemy).getBV();
        when(client.getLocalPlayer()).thenReturn(local);
        Client botClient = mock(Client.class);
        when(botClient.getLocalPlayer()).thenReturn(bot);
        bots.put("Bot", botClient);
        when(client.getBots()).thenReturn(bots);
        when(game.getPlayersList()).thenReturn(List.of(enemy, local, bot));
        units(local, 2);
        units(bot, 1);
        units(enemy, 3);
        when(client.getHost()).thenReturn("localhost");
        when(client.getPort()).thenReturn(2346);
        MapSettings map = MapSettings.getInstance();
        map.setBoardSize(16, 17);
        map.setMapSize(1, 1);
        when(client.getMapSettings()).thenReturn(map);
    }

    private void units(Player player, int count) {
        ArrayList<Entity> entities = new ArrayList<>();
        IntStream.range(0, count).forEach(i -> entities.add(mock(Entity.class)));
        when(game.getPlayerEntities(player, false)).thenReturn(entities);
    }

    private LobbySnapshot snapshot() {
        return LobbySnapshot.of(() -> client, List.of("Local: hello"));
    }

    @Test
    void groupsPlayersByTeamInTeamOrder() {
        LobbySnapshot snapshot = snapshot();

        assertEquals(List.of(1, 2), snapshot.teams().stream().map(LobbySnapshot.TeamGroup::id).toList());
        assertEquals(List.of("Local", "Bot"), snapshot.teams().get(0).players().stream()
              .map(LobbySnapshot.PlayerRow::name).toList());
        assertEquals(Messages.getString("Lobby.team", 2), snapshot.teams().get(1).name());
        assertEquals(1, snapshot.localPlayerId());
        assertEquals(3, snapshot.playerCount());
    }

    @Test
    void playersWithoutTeamShareTheNoTeamGroup() {
        enemy.setTeam(Player.TEAM_UNASSIGNED);
        bot.setTeam(Player.TEAM_NONE);

        List<LobbySnapshot.TeamGroup> teams = snapshot().teams();
        LobbySnapshot.TeamGroup last = teams.get(teams.size() - 1);

        assertEquals(Player.TEAM_NONE, last.id());
        assertEquals(Messages.getString("Lobby.noTeam"), last.name());
        assertEquals(List.of("Bot", "Enemy"), last.players().stream().map(LobbySnapshot.PlayerRow::name).toList());
        assertEquals(LobbySnapshot.Relation.NONE, last.relation());
    }

    @Test
    void marksTheLocalPlayerItsBotsAndRemotePlayers() {
        List<LobbySnapshot.PlayerRow> players = snapshot().players();
        LobbySnapshot.PlayerRow self = players.get(0);
        LobbySnapshot.PlayerRow localBot = players.get(1);
        LobbySnapshot.PlayerRow remote = players.get(2);

        assertTrue(self.self());
        assertTrue(self.local());
        assertFalse(self.bot());
        assertFalse(localBot.self());
        assertTrue(localBot.local());
        assertTrue(localBot.bot());
        assertFalse(remote.local());
    }

    @Test
    void countsReadyPlayers() {
        bot.setDone(true);
        enemy.setDone(true);

        LobbySnapshot snapshot = snapshot();

        assertEquals(2, snapshot.readyCount());
        assertFalse(snapshot.localReady());
        local.setDone(true);
        assertTrue(snapshot().localReady());
    }

    @Test
    void showsUnitsAndBattleValueCountingSquadronFightersOnce() {
        ArrayList<Entity> entities = new ArrayList<>(List.of(mock(Entity.class), mock(Entity.class),
              mock(FighterSquadron.class)));
        when(game.getPlayerEntities(local, false)).thenReturn(entities);

        LobbySnapshot.PlayerRow self = snapshot().players().get(0);

        assertEquals(2, self.units());
        assertEquals(1200, self.bv());
        assertFalse(self.hidden());
    }

    @Test
    void blindDropHidesEnemyForces() {
        when(options.booleanOption(OptionsConstants.BASE_BLIND_DROP)).thenReturn(true);

        List<LobbySnapshot.PlayerRow> players = snapshot().players();

        LobbySnapshot.PlayerRow hidden = players.get(2);
        assertTrue(hidden.hidden());
        assertEquals(0, hidden.units());
        assertEquals(0, hidden.bv());
        assertFalse(players.get(1).hidden());
        assertEquals(1, players.get(1).units());
    }

    @Test
    void realBlindDropHidesEnemyForcesButNotFromTheGameMaster() {
        when(options.booleanOption(OptionsConstants.BASE_REAL_BLIND_DROP)).thenReturn(true);
        assertTrue(snapshot().players().get(2).hidden());

        local.setGameMaster(true);
        LobbySnapshot snapshot = snapshot();

        assertTrue(snapshot.localGameMaster());
        assertFalse(snapshot.players().get(2).hidden());
        assertEquals(3000, snapshot.players().get(2).bv());
        assertTrue(snapshot.players().get(0).gameMaster());
    }

    @Test
    void summarisesTheMapAndTheServer() {
        LobbySnapshot snapshot = snapshot();

        assertEquals(Messages.getString("Lobby.mapOneBoard", 16, 17), snapshot.mapSummary());
        assertEquals(Messages.getString("Lobby.serverLocal", "2346"), snapshot.server());
        assertEquals(List.of(new ChatLine(ChatLine.Kind.PLAYER, "Local", "hello", LobbySnapshot.Relation.OWN)),
              snapshot.chat());
    }

    @Test
    void summarisesSeveralBoardsAndARemoteServer() {
        MapSettings map = MapSettings.getInstance();
        map.setBoardSize(16, 17);
        map.setMapSize(2, 3);
        when(client.getMapSettings()).thenReturn(map);
        when(client.getHost()).thenReturn("example.org");

        LobbySnapshot snapshot = snapshot();

        assertEquals(Messages.getString("Lobby.mapBoards", 2, 3, 16, 17), snapshot.mapSummary());
        assertEquals(Messages.getString("Lobby.serverRemote", "example.org", "2346"), snapshot.server());
    }

    @Test
    void keepsOnlyTheRecentChat() {
        List<String> chat = IntStream.range(0, LobbySnapshot.CHAT_LINES + 5).mapToObj(String::valueOf).toList();

        List<String> kept = LobbySnapshot.of(() -> client, chat).chat().stream().map(ChatLine::text).toList();

        assertEquals(LobbySnapshot.CHAT_LINES, kept.size());
        assertEquals("5", kept.get(0));
        assertEquals(String.valueOf(LobbySnapshot.CHAT_LINES + 4), kept.get(kept.size() - 1));
    }

    @Test
    void snapshotsDoNotChangeWithTheGame() {
        LobbySnapshot snapshot = snapshot();

        local.setDone(true);
        local.setName("Renamed");

        assertFalse(snapshot.localReady());
        assertEquals("Local", snapshot.players().get(0).name());
    }

    private static LobbySnapshot.PlayerRow row(int id, String name, int team, boolean self, boolean observer,
          boolean hidden, int bv) {
        return new LobbySnapshot.PlayerRow(id, name, team, false, self, self, false, observer, false, false, hidden, 1,
              bv, 0, "", true, 0, "");
    }

    @Test
    void countsOnlyThePlayersTheServerWaitsFor() {
        LobbySnapshot.PlayerRow ghost = new LobbySnapshot.PlayerRow(3, "Gone", 1, false, false, false, false, false,
              false, true, false, 1, 0, 0, "", true, 0, "");
        LobbySnapshot snapshot = snapshotOf(List.of(new LobbySnapshot.TeamGroup(1, "Team 1",
              List.of(row(1, "Me", 1, true, false, false, 0), row(2, "Watcher", 1, false, true, false, 0), ghost,
                    row(4, "Ally", 1, false, false, false, 0).withReady(true)))));
        assertEquals(1, snapshot.readyCount());
        assertEquals(2, snapshot.readyTotal());
        assertEquals(List.of("Me"), snapshot.waitingFor());
        assertEquals(4, snapshot.playerCount());
    }

    @Test
    void showsTheLocalPlayerAndTheirBotsReadyAtOnce() {
        LobbySnapshot snapshot = snapshot().withLocalReady(true);

        assertTrue(snapshot.localReady());
        assertTrue(snapshot.players().get(0).ready());
        assertTrue(snapshot.players().get(1).ready());
        assertFalse(snapshot.players().get(2).ready());
        assertEquals(2, snapshot.readyCount());
        assertEquals(List.of("Enemy"), snapshot.waitingFor());
        assertFalse(snapshot.withLocalReady(false).players().get(1).ready());
    }

    @Test
    void readsTheSides() {
        LobbySnapshot snapshot = snapshot();

        assertEquals(List.of(new LobbyReadiness.Side(Messages.getString("Lobby.team", 1),
                    LobbySnapshot.Relation.OWN, false, 2000),
              new LobbyReadiness.Side(Messages.getString("Lobby.team", 2), LobbySnapshot.Relation.ENEMY, false,
                    3000)), snapshot.sides());
    }
    private static LobbySnapshot snapshotOf(List<LobbySnapshot.TeamGroup> teams) {
        return new LobbySnapshot(1, false, false, LobbyGameMaster.OFF, teams, List.of(), List.of(), "", "",
              LobbyForces.EMPTY,
              LobbyBattlefield.EMPTY, LobbyReadiness.EMPTY, LobbyRequisition.EMPTY);
    }

    @Test
    void putsTheLocalTeamFirstAndTheLocalPlayerFirstInIt() {
        local.setTeam(3);
        enemy.setTeam(1);
        Player ally = spy(new Player(0, "Ally"));
        ally.setTeam(3);
        doReturn(0).when(ally).getBV();
        units(ally, 0);
        when(game.getPlayersList()).thenReturn(List.of(ally, enemy, local, bot));

        LobbySnapshot snapshot = snapshot();

        assertEquals(List.of(3, 1), snapshot.teams().stream().map(LobbySnapshot.TeamGroup::id).toList());
        assertEquals(List.of("Local", "Ally"), snapshot.teams().get(0).players().stream()
              .map(LobbySnapshot.PlayerRow::name).toList());
        assertEquals(LobbySnapshot.Relation.OWN, snapshot.teams().get(0).relation());
        assertEquals(LobbySnapshot.Relation.ENEMY, snapshot.teams().get(1).relation());
        assertEquals(3, snapshot.localTeam());
    }

    @Test
    void sumsTeamsUpWithoutTheirHiddenForces() {
        when(options.booleanOption(OptionsConstants.BASE_BLIND_DROP)).thenReturn(true);
        ArrayList<Entity> heavy = new ArrayList<>();
        Entity atlas = mock(Entity.class);
        when(atlas.getWeight()).thenReturn(100.0);
        Entity locust = mock(Entity.class);
        when(locust.getWeight()).thenReturn(20.0);
        heavy.add(atlas);
        heavy.add(locust);
        when(game.getPlayerEntities(local, false)).thenReturn(heavy);

        LobbySnapshot snapshot = snapshot();
        LobbySnapshot.TeamGroup own = snapshot.teams().get(0);
        LobbySnapshot.TeamGroup foes = snapshot.teams().get(1);

        assertEquals(2000, own.bv());
        assertEquals(3, own.units());
        assertEquals(120, own.tons());
        assertEquals(120, own.players().get(0).tons());
        assertFalse(own.hidden());
        assertTrue(foes.hidden());
        assertEquals(0, foes.bv());
        assertEquals(Messages.getString("ChatLounge.Blind"), foes.players().get(0).start());
        assertTrue(foes.players().get(0).startValid());
    }

    @Test
    void namesTheDeploymentArea() {
        local.setStartingPos(2);
        local.setStartWidth(3);
        local.setStartOffset(0);

        assertEquals("N", snapshot().players().get(0).start());
        local.setStartOffset(2);
        assertEquals("N, 2, 3", snapshot().players().get(0).start());
        assertEquals("N", LobbySnapshot.deploymentName(2));
        assertEquals(Messages.getString("Lobby.zone", 4),
              LobbySnapshot.deploymentName(megamek.common.board.Board.encodeCustomDeploymentZoneID(4)));
    }

    @Test
    void marksGhostsAndLocalBots() {
        doReturn(true).when(enemy).isGhost();

        List<LobbySnapshot.PlayerRow> players = snapshot().players();

        assertTrue(players.get(2).ghost());
        assertFalse(players.get(0).ghost());
        assertTrue(players.get(1).localBot());
        assertFalse(players.get(0).localBot());
        assertFalse(players.get(2).localBot());
    }

    @Test
    void offersTheGameMasterRoleOnlyWhenAllowedAndFree() {
        assertFalse(snapshot().gameMasterAvailable());

        when(options.booleanOption(OptionsConstants.GAME_MASTER_ALLOW)).thenReturn(true);
        assertTrue(snapshot().gameMasterAvailable());

        enemy.setGameMaster(true);
        assertFalse(snapshot().gameMasterAvailable());

        enemy.setGameMaster(false);
        local.setGameMaster(true);
        assertTrue(snapshot().gameMasterAvailable());
    }

    @Test
    void showsTheRunningGameMasterVote() {
        when(options.booleanOption(OptionsConstants.GAME_MASTER_ALLOW)).thenReturn(true);
        when(game.getPlayer(3)).thenReturn(enemy);
        when(game.getPlayer(1)).thenReturn(local);
        when(client.getGameMasterPoll()).thenReturn(new Poll(3, List.of(1, 3), VoteThreshold.UNANIMOUS));

        LobbySnapshot snapshot = snapshot();

        assertEquals(LobbyGameMaster.Seat.VOTE, snapshot.gameMaster().seat());
        assertEquals("Enemy", snapshot.gameMaster().requester());
        assertTrue(snapshot.gameMaster().awaitsSelf());
        assertFalse(snapshot.gameMasterAvailable());
    }

    @Test
    void findsTheFirstFreeTeam() {
        assertEquals(3, snapshot().freeTeam());

        List<LobbySnapshot.TeamGroup> full = IntStream.range(1, LobbySnapshot.TEAM_COUNT)
              .mapToObj(team -> new LobbySnapshot.TeamGroup(team, "Team " + team,
                    List.of(row(team, "P" + team, team, team == 1, false, false, 0))))
              .toList();
        assertEquals(Player.TEAM_NONE, snapshotOf(full).freeTeam());
    }

    @Test
    void keepsTheCustomZonesSorted() {
        LobbySnapshot snapshot = LobbySnapshot.of(() -> client, List.of(), List.of(5, 2));

        assertEquals(List.of(2, 5), snapshot.customZones());
    }
}
