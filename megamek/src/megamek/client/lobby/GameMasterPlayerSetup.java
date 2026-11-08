/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import jakarta.annotation.Nullable;
import megamek.client.ui.Messages;
import megamek.common.Player;
import megamek.common.game.Game;
import megamek.common.interfaces.IStartingPositions;

/**
 * A Game Master putting a player on a team and choosing the edge their units arrive from, as the player setup card
 * fills it in. This is what turns somebody who joined a running game into somebody playing it: they arrive on no team
 * and with no deployment zone, and the lobby that would set both is long gone.
 *
 * <p>Both choices start on what the chosen player already has, and Apply sends only what changed, as the chat
 * commands {@code /changeTeam} and {@code /changeDeploymentZone} the Swing dialog sent.</p>
 */
public final class GameMasterPlayerSetup {
    /** Stands for the first team nobody is on, resolved when the change is applied. */
    static final int NEW_TEAM = Integer.MIN_VALUE;

    private final Game game;
    private final List<Player> players = new ArrayList<>();
    private final List<Integer> teams = new ArrayList<>();
    private int player = -1;
    private int team;
    private int zone;

    /**
     * @param game    the game as the Game Master's client sees it
     * @param localId the Game Master's own player id; they set themselves up as any player does
     * @param startOn the player to open on, or null for the first one offered
     */
    public GameMasterPlayerSetup(Game game, int localId, @Nullable Player startOn) {
        this.game = game;
        for (Player each : game.getPlayersList()) {
            // a player who dropped out is not connected, and setting them up achieves nothing
            if (each.getId() != localId && !each.isGhost()) {
                players.add(each);
            }
        }
        // the teams that exist, plus the one a player sits on before they are given any
        teams.add(Player.TEAM_UNASSIGNED);
        for (int teamId = 0; teamId < Player.TEAM_NAMES.length; teamId++) {
            teams.add(teamId);
        }
        if (firstUnusedTeam() != Player.TEAM_UNASSIGNED) {
            teams.add(NEW_TEAM);
        }
        int first = 0;
        if (startOn != null) {
            for (int index = 0; index < players.size(); index++) {
                if (players.get(index).getId() == startOn.getId()) {
                    first = index;
                }
            }
        }
        player(players.isEmpty() ? -1 : first);
    }

    /** @return the names of the players who may be set up, in game order */
    public List<String> playerNames() {
        List<String> names = new ArrayList<>();
        for (Player each : players) {
            names.add(each.getName());
        }
        return names;
    }

    /** @return the chosen player's index in {@link #playerNames()}, or -1 when there is nobody to set up */
    public int player() {
        return player;
    }

    /** Chooses a player and loads the team and zone they already have. */
    public void player(int index) {
        if (index < 0 || index >= players.size()) {
            return;
        }
        player = index;
        Player chosen = players.get(index);
        team = Math.max(0, teams.indexOf(chosen.getTeam()));
        int startingPos = chosen.getStartingPos();
        zone = (startingPos >= 0 && startingPos < IStartingPositions.START_LOCATION_NAMES.length) ? startingPos : 0;
    }

    /** @return the chosen player, or null when there is nobody to set up */
    public @Nullable Player chosenPlayer() {
        return player < 0 ? null : players.get(player);
    }

    /** @return the team choices: no team, each team with who is on it, and a new team while one is free */
    public List<String> teamNames() {
        List<String> names = new ArrayList<>();
        for (int teamId : teams) {
            names.add(teamName(teamId));
        }
        return names;
    }

    private String teamName(int teamId) {
        if (teamId == NEW_TEAM) {
            return Messages.getString("GameMasterPlayerSetupDialog.newTeam");
        }
        String name = (teamId == Player.TEAM_UNASSIGNED)
              ? Messages.getString("GameMasterPlayerSetupDialog.noTeam") : Player.TEAM_NAMES[teamId];
        List<String> members = membersOf(teamId);
        return members.isEmpty() ? name : name + " - " + String.join(", ", members);
    }

    public int team() {
        return team;
    }

    public void team(int index) {
        team = Math.max(0, Math.min(teams.size() - 1, index));
    }

    /** @return the deployment zones, named as the lobby names them */
    public static List<String> zoneNames() {
        return List.of(IStartingPositions.START_LOCATION_NAMES);
    }

    public int zone() {
        return zone;
    }

    public void zone(int index) {
        zone = Math.max(0, Math.min(IStartingPositions.START_LOCATION_NAMES.length - 1, index));
    }

    /** @return why the choices cannot be applied, or null when they can; the server refuses these or they end a game */
    public @Nullable String problem() {
        Player chosen = chosenPlayer();
        if (chosen == null) {
            return null;
        }
        int chosenTeam = resolvedTeam();
        if (chosenTeam == Player.TEAM_UNASSIGNED && !game.getPlayerEntities(chosen, false).isEmpty()) {
            return Messages.getString("GameMasterPlayerSetupDialog.refused.unassignedWithUnits", chosen.getName());
        }
        if (wouldLeaveOneTeam(chosen, chosenTeam)) {
            return Messages.getString("GameMasterPlayerSetupDialog.refused.oneTeamLeft");
        }
        return null;
    }

    /** @return the line saying whether the choices can be applied, and why not */
    public String status() {
        String problem = problem();
        return problem == null ? Messages.getString("GameMasterPlayerSetupDialog.ready")
              : Messages.getString("GameMasterPlayerSetupDialog.refused", problem);
    }

    /** @return whether a force may be handed over: a player is chosen and the setup can be applied first */
    public boolean mayReinforce() {
        return chosenPlayer() != null && problem() == null;
    }

    /**
     * Sends whichever of team and zone changed, through {@code chat}; nothing for an unchanged value.
     *
     * @return the commands sent
     */
    public List<String> apply(Consumer<String> chat) {
        List<String> sent = new ArrayList<>();
        Player chosen = chosenPlayer();
        if (chosen == null) {
            return sent;
        }
        int chosenTeam = resolvedTeam();
        if (chosenTeam != chosen.getTeam()) {
            sent.add("/changeTeam playerID=" + chosen.getId() + " teamID=" + chosenTeam);
        }
        if (zone != chosen.getStartingPos()) {
            sent.add("/changeDeploymentZone playerID=" + chosen.getId() + " zoneID=" + zone);
        }
        for (String command : sent) {
            chat.accept(command);
        }
        return sent;
    }

    private int resolvedTeam() {
        int teamId = teams.get(team);
        return teamId == NEW_TEAM ? firstUnusedTeam() : teamId;
    }

    /**
     * Whether the change would leave everybody still playing on one team: the victory check runs right after a team
     * change, so that would end the game rather than set somebody up in it.
     */
    private boolean wouldLeaveOneTeam(Player moving, int chosenTeam) {
        if (chosenTeam == Player.TEAM_UNASSIGNED || chosenTeam == Player.TEAM_NONE) {
            // lone wolves are everybody's enemy
            return false;
        }
        for (Player other : game.getPlayersList()) {
            boolean playing = !other.isGhost() && other.getTeam() != Player.TEAM_UNASSIGNED;
            if (other.getId() != moving.getId() && playing && other.getTeam() != chosenTeam) {
                return false;
            }
        }
        return true;
    }

    private List<String> membersOf(int teamId) {
        List<String> names = new ArrayList<>();
        for (Player each : game.getPlayersList()) {
            if (each.getTeam() == teamId) {
                names.add(each.getName());
            }
        }
        return names;
    }

    /** @return the lowest team nobody is on, or {@link Player#TEAM_UNASSIGNED} when every team is in use */
    private int firstUnusedTeam() {
        for (int teamId = Player.TEAM_NONE + 1; teamId < Player.TEAM_NAMES.length; teamId++) {
            boolean used = false;
            for (Player each : game.getPlayersList()) {
                used |= each.getTeam() == teamId;
            }
            if (!used) {
                return teamId;
            }
        }
        return Player.TEAM_UNASSIGNED;
    }
}
