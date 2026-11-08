/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import megamek.common.Player;
import megamek.common.game.Game;
import megamek.common.units.Entity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GameMasterPlayerSetupTest {

    private Game game;
    private Player gameMaster;
    private Player first;
    private Player second;
    private Player joiner;

    @BeforeEach
    void setUp() {
        game = mock(Game.class);
        gameMaster = player(1, "Master", 1, 0);
        first = player(2, "First", 1, 1);
        second = player(3, "Second", 2, 2);
        joiner = player(4, "Joiner", Player.TEAM_UNASSIGNED, 0);
        when(game.getPlayersList()).thenReturn(List.of(gameMaster, first, second, joiner));
        when(game.getPlayerEntities(any(Player.class), anyBoolean())).thenReturn(new ArrayList<>());
    }

    private static Player player(int id, String name, int team, int zone) {
        Player player = new Player(id, name);
        player.setTeam(team);
        player.setStartingPos(zone);
        return player;
    }

    @Test
    void offersEveryoneButTheGameMasterAndOpensOnTheChosenPlayer() {
        GameMasterPlayerSetup setup = new GameMasterPlayerSetup(game, gameMaster.getId(), joiner);

        assertEquals(List.of("First", "Second", "Joiner"), setup.playerNames());
        assertEquals(2, setup.player());
        assertEquals(joiner, setup.chosenPlayer());
        assertEquals(0, setup.team(), "the joiner sits on no team yet");
        assertEquals("Any", GameMasterPlayerSetup.zoneNames().get(0));
    }

    @Test
    void putsAJoinerOnATeamAndAnEdgeWithTheSwingCommands() {
        GameMasterPlayerSetup setup = new GameMasterPlayerSetup(game, gameMaster.getId(), joiner);
        setup.team(3);
        setup.zone(3);
        List<String> chat = new ArrayList<>();

        List<String> sent = setup.apply(chat::add);

        assertEquals(List.of("/changeTeam playerID=4 teamID=2", "/changeDeploymentZone playerID=4 zoneID=3"), sent);
        assertEquals(sent, chat);
        assertNull(setup.problem());
        assertTrue(setup.mayReinforce());
    }

    @Test
    void sendsNothingForWhatDidNotChange() {
        GameMasterPlayerSetup setup = new GameMasterPlayerSetup(game, gameMaster.getId(), first);
        List<String> chat = new ArrayList<>();

        assertTrue(setup.apply(chat::add).isEmpty());
        assertTrue(chat.isEmpty());
    }

    @Test
    void aNewTeamIsTheFirstOneNobodyIsOn() {
        GameMasterPlayerSetup setup = new GameMasterPlayerSetup(game, gameMaster.getId(), joiner);
        List<String> teams = setup.teamNames();
        setup.team(teams.size() - 1);
        setup.zone(0);

        assertEquals(List.of("/changeTeam playerID=4 teamID=3"), setup.apply(command -> { }));
        assertTrue(teams.get(2).contains("Master") && teams.get(2).contains("First"),
              "a team names who is on it");
    }

    @Test
    void refusesToLeaveAPlayerWithUnitsOnNoTeam() {
        when(game.getPlayerEntities(first, false)).thenReturn(new ArrayList<>(List.of(mock(Entity.class))));
        GameMasterPlayerSetup setup = new GameMasterPlayerSetup(game, gameMaster.getId(), first);
        setup.team(0);

        assertNotNull(setup.problem());
        assertFalse(setup.mayReinforce());
        assertTrue(setup.status().contains("First"));
    }

    @Test
    void refusesAChangeThatLeavesOneTeamPlaying() {
        GameMasterPlayerSetup setup = new GameMasterPlayerSetup(game, gameMaster.getId(), second);
        setup.team(2);

        assertNotNull(setup.problem(), "everyone on one team would end the game");
    }
}
