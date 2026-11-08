/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import megamek.common.Player;
import megamek.common.game.Game;
import megamek.common.options.OptionsConstants;
import megamek.common.voting.Poll;
import megamek.common.voting.VoteChoice;
import megamek.common.voting.VoteThreshold;

/**
 * The Game Master seat as the lobby shows it: whether the game allows one, who holds it, and the vote on a request for
 * it while one runs.
 *
 * @param seat      where the seat stands for the local player
 * @param holder    the Game Master's name while someone holds the role, else empty
 * @param requester the name of the player who asked for the role while a vote runs, else empty
 * @param ballots   each voter's ballot in the running vote, in the order the server lists them; empty without a vote
 * @param majority  whether the vote passes on more yes than no votes rather than needing everyone
 */
public record LobbyGameMaster(Seat seat, String holder, String requester, List<Ballot> ballots, boolean majority) {

    /** No game yet, or a game without a Game Master. */
    public static final LobbyGameMaster OFF = new LobbyGameMaster(Seat.OFF, "", "", List.of(), false);

    public LobbyGameMaster {
        Objects.requireNonNull(seat);
        Objects.requireNonNull(holder);
        Objects.requireNonNull(requester);
        ballots = List.copyOf(ballots);
    }

    /** Where the seat stands for the local player. */
    public enum Seat {
        /** The game options allow no Game Master. */
        OFF,
        /** Nobody holds the role; the local player may call a vote for it. */
        OPEN,
        /** A vote on someone's request runs. */
        VOTE,
        /** The local player is the Game Master. */
        MINE,
        /** Another player is the Game Master. */
        HELD
    }

    /** A voter's choice. */
    public enum Choice {
        YES, NO, PENDING
    }

    /**
     * One voter's ballot.
     *
     * @param name   the voter's name
     * @param choice what they chose so far
     * @param self   whether this is the local player
     */
    public record Ballot(String name, Choice choice, boolean self) {
        public Ballot {
            Objects.requireNonNull(name);
            Objects.requireNonNull(choice);
        }
    }

    /** @return whether the local player may take the role, or give it up */
    public boolean actionable() {
        return seat == Seat.OPEN || seat == Seat.MINE;
    }

    /** @return whether the local player asked for the role in the running vote, and so may withdraw the request */
    public boolean requestedBySelf() {
        return seat == Seat.VOTE && !ballots.isEmpty() && ballots.getFirst().self();
    }

    /** @return whether the running vote waits for the local player's ballot */
    public boolean awaitsSelf() {
        return seat == Seat.VOTE && ballots.stream().anyMatch(ballot -> ballot.self()
              && ballot.choice() == Choice.PENDING);
    }

    /**
     * @return whether the local player has a ballot in the running vote and did not call it, and so may cast it or
     *       change it while the vote runs
     */
    public boolean votesSelf() {
        if (seat != Seat.VOTE || requestedBySelf()) {
            return false;
        }
        for (Ballot ballot : ballots) {
            if (ballot.self()) {
                return true;
            }
        }
        return false;
    }

    /** @return the ballot of the local player in the running vote, or null when they have none */
    public Ballot selfBallot() {
        if (seat == Seat.VOTE) {
            for (Ballot ballot : ballots) {
                if (ballot.self()) {
                    return ballot;
                }
            }
        }
        return null;
    }

    /** @return the chat command that casts the local player's vote: allow or deny the request */
    public static String voteCommand(boolean yes) {
        return yes ? LobbyCommands.GAME_MASTER_ALLOW_COMMAND : LobbyCommands.GAME_MASTER_DENY_COMMAND;
    }

    /** @return how many ballots chose {@code choice} */
    public int count(Choice choice) {
        return (int) ballots.stream().filter(ballot -> ballot.choice() == choice).count();
    }

    /**
     * Reads the seat. A vote that runs wins over the role being held or the game no longer allowing one, as the server
     * keeps the vote open until it resolves.
     *
     * @param game  the game as the local client sees it
     * @param local the local player, or null before the server assigned one
     * @param poll  the running Game Master vote, or null when none runs
     */
    public static LobbyGameMaster of(Game game, Player local, Poll poll) {
        if (local == null) {
            return OFF;
        }
        if (poll != null && !poll.getStatus().isResolved()) {
            Player requester = game.getPlayer(poll.getRequesterId());
            List<Ballot> ballots = new ArrayList<>();
            // The requester leads, as the one who asked
            ballots.add(ballot(game, local, poll.getRequesterId(), VoteChoice.YES));
            for (Map.Entry<Integer, VoteChoice> vote : poll.getVotes().entrySet()) {
                if (vote.getKey() != poll.getRequesterId()) {
                    ballots.add(ballot(game, local, vote.getKey(), vote.getValue()));
                }
            }
            return new LobbyGameMaster(Seat.VOTE, "", requester == null ? "" : requester.getName(), ballots,
                  poll.getThreshold() == VoteThreshold.MAJORITY);
        }
        for (Player player : game.getPlayersList()) {
            if (player.isGameMaster()) {
                boolean mine = player.getId() == local.getId();
                return new LobbyGameMaster(mine ? Seat.MINE : Seat.HELD, player.getName(), "", List.of(), false);
            }
        }
        if (!game.getOptions().booleanOption(OptionsConstants.GAME_MASTER_ALLOW)) {
            return OFF;
        }
        return new LobbyGameMaster(Seat.OPEN, "", "", List.of(), false);
    }

    private static Ballot ballot(Game game, Player local, int id, VoteChoice choice) {
        Player voter = game.getPlayer(id);
        Choice shown = switch (choice) {
            case YES -> Choice.YES;
            case NO -> Choice.NO;
            default -> Choice.PENDING;
        };
        return new Ballot(voter == null ? "" : voter.getName(), shown, id == local.getId());
    }
}
