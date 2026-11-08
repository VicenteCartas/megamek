/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import megamek.client.lobby.LobbyGameMaster.Ballot;
import megamek.client.lobby.LobbyGameMaster.Choice;
import megamek.client.lobby.LobbyGameMaster.Seat;
import megamek.common.Player;
import megamek.common.game.Game;
import megamek.common.options.GameOptions;
import megamek.common.options.OptionsConstants;
import megamek.common.voting.Poll;
import megamek.common.voting.VoteThreshold;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LobbyGameMasterTest {

    private Game game;
    private GameOptions options;
    private Player local;
    private Player other;
    private Player third;

    @BeforeEach
    void setUp() {
        game = mock(Game.class);
        options = mock(GameOptions.class);
        when(game.getOptions()).thenReturn(options);
        local = new Player(1, "Local");
        other = new Player(2, "Other");
        third = new Player(3, "Third");
        when(game.getPlayersList()).thenReturn(List.of(local, other, third));
        when(game.getPlayer(1)).thenReturn(local);
        when(game.getPlayer(2)).thenReturn(other);
        when(game.getPlayer(3)).thenReturn(third);
    }

    private void allow(boolean allowed) {
        when(options.booleanOption(OptionsConstants.GAME_MASTER_ALLOW)).thenReturn(allowed);
    }

    @Test
    void isOffWithoutALocalPlayerOrWhenTheGameAllowsNone() {
        allow(true);
        assertEquals(LobbyGameMaster.OFF, LobbyGameMaster.of(game, null, null));

        allow(false);
        LobbyGameMaster seat = LobbyGameMaster.of(game, local, null);
        assertEquals(Seat.OFF, seat.seat());
        assertFalse(seat.actionable());
    }

    @Test
    void isOpenWhenAllowedAndFree() {
        allow(true);

        LobbyGameMaster seat = LobbyGameMaster.of(game, local, null);

        assertEquals(Seat.OPEN, seat.seat());
        assertTrue(seat.actionable());
        assertEquals("", seat.holder());
    }

    @Test
    void namesWhoHoldsTheSeatEvenOnceTheGameNoLongerAllowsOne() {
        allow(false);
        other.setGameMaster(true);

        LobbyGameMaster held = LobbyGameMaster.of(game, local, null);
        assertEquals(Seat.HELD, held.seat());
        assertEquals("Other", held.holder());
        assertFalse(held.actionable());

        other.setGameMaster(false);
        local.setGameMaster(true);
        LobbyGameMaster mine = LobbyGameMaster.of(game, local, null);
        assertEquals(Seat.MINE, mine.seat());
        assertEquals("Local", mine.holder());
        assertTrue(mine.actionable());
    }

    @Test
    void aRunningVoteLeadsWithItsRequesterAndWaitsForTheOthers() {
        allow(true);
        Poll poll = new Poll(2, List.of(1, 2, 3), VoteThreshold.UNANIMOUS);

        LobbyGameMaster vote = LobbyGameMaster.of(game, local, poll);

        assertEquals(Seat.VOTE, vote.seat());
        assertEquals("Other", vote.requester());
        assertEquals(List.of(new Ballot("Other", Choice.YES, false), new Ballot("Local", Choice.PENDING, true),
              new Ballot("Third", Choice.PENDING, false)), vote.ballots());
        assertFalse(vote.majority());
        assertTrue(vote.awaitsSelf());
        assertFalse(vote.requestedBySelf());
        assertFalse(vote.actionable());
        assertEquals(1, vote.count(Choice.YES));
        assertEquals(2, vote.count(Choice.PENDING));
    }

    @Test
    void followsTheBallotsAsTheyCome() {
        allow(true);
        Poll poll = new Poll(2, List.of(1, 2, 3), VoteThreshold.MAJORITY);
        poll.castVote(1, false);

        LobbyGameMaster vote = LobbyGameMaster.of(game, local, poll);

        assertTrue(vote.majority());
        assertFalse(vote.awaitsSelf());
        assertEquals(Choice.NO, vote.ballots().get(1).choice());
        assertEquals(1, vote.count(Choice.NO));
    }

    @Test
    void theRequesterMayWithdrawButHasNoBallotToCast() {
        allow(true);
        Poll poll = new Poll(1, List.of(1, 2), VoteThreshold.UNANIMOUS);

        LobbyGameMaster vote = LobbyGameMaster.of(game, local, poll);

        assertTrue(vote.requestedBySelf());
        assertFalse(vote.awaitsSelf());
        assertTrue(vote.ballots().get(0).self());
    }

    @Test
    void aResolvedVoteShowsTheSeatAgain() {
        allow(true);
        Poll poll = new Poll(2, List.of(1, 2), VoteThreshold.UNANIMOUS);
        poll.castVote(1, true);
        other.setGameMaster(true);

        LobbyGameMaster seat = LobbyGameMaster.of(game, local, poll);

        assertEquals(Seat.HELD, seat.seat());
        assertTrue(seat.ballots().isEmpty());
    }

    @Test
    void aVoterMayCastAndChangeTheirBallotButTheRequesterHasNone() {
        allow(true);
        Poll poll = new Poll(2, List.of(1, 2, 3), VoteThreshold.UNANIMOUS);
        poll.castVote(1, true);

        LobbyGameMaster voter = LobbyGameMaster.of(game, local, poll);
        assertTrue(voter.votesSelf());
        assertEquals(new Ballot("Local", Choice.YES, true), voter.selfBallot());

        LobbyGameMaster requester = LobbyGameMaster.of(game, other, poll);
        assertFalse(requester.votesSelf());
        assertTrue(requester.requestedBySelf());
        assertFalse(LobbyGameMaster.of(game, local, null).votesSelf());
        assertNull(LobbyGameMaster.of(game, local, null).selfBallot());
    }

    @Test
    void aBallotIsCastWithTheAllowAndDenyCommands() {
        assertEquals("/allowGM", LobbyGameMaster.voteCommand(true));
        assertEquals("/denyGM", LobbyGameMaster.voteCommand(false));
    }
}
