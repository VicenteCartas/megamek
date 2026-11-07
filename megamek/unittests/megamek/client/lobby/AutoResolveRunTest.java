/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import megamek.client.lobby.AutoResolveRun.Outcome;
import megamek.client.lobby.AutoResolveRun.Score;
import megamek.common.Player;
import megamek.server.victory.VictoryResult;
import org.junit.jupiter.api.Test;

class AutoResolveRunTest {
    private static final int TEAM = 2;
    private static final int PLAYER = 5;

    private static VictoryResult won(int player, int team) {
        return new VictoryResult(true, player, team);
    }

    @Test
    void theOutcomeIsTheLocalSidesTeamOrPlayer() {
        assertEquals(Outcome.VICTORY, AutoResolveRun.outcome(won(Player.PLAYER_NONE, TEAM), TEAM, PLAYER));
        assertEquals(Outcome.DEFEAT, AutoResolveRun.outcome(won(Player.PLAYER_NONE, 3), TEAM, PLAYER));
        assertEquals(Outcome.VICTORY, AutoResolveRun.outcome(won(PLAYER, Player.TEAM_NONE), Player.TEAM_NONE, PLAYER),
              "a player without a team wins as a player");
        assertEquals(Outcome.DEFEAT, AutoResolveRun.outcome(won(7, Player.TEAM_NONE), Player.TEAM_NONE, PLAYER));
        assertEquals(Outcome.DRAW, AutoResolveRun.outcome(VictoryResult.drawResult(), TEAM, PLAYER));
        assertEquals(Outcome.DRAW, AutoResolveRun.outcome(VictoryResult.drawResult(), Player.TEAM_NONE, PLAYER),
              "a draw is no victory for a player without a team");
    }

    @Test
    void theScoreCountsDrawsAndFailuresApart() {
        List<VictoryResult> results = new ArrayList<>(Arrays.asList(won(Player.PLAYER_NONE, TEAM),
              won(Player.PLAYER_NONE, TEAM), won(Player.PLAYER_NONE, 3), VictoryResult.drawResult(), null));
        Score score = AutoResolveRun.score(results, TEAM, PLAYER);
        assertEquals(new Score(4, 2, 1, 1, 1), score);
        assertEquals(50, score.percent(), "the chance is of the battles that ended");
        assertEquals(0, new Score(0, 0, 0, 0, 3).percent());
    }

    @Test
    void theWinnerLineNamesTheTeamOrThePlayer() {
        assertTrue(AutoResolveRun.winner(won(Player.PLAYER_NONE, 3), id -> "nobody").contains("3"));
        assertTrue(AutoResolveRun.winner(won(4, Player.TEAM_NONE), id -> id == 4 ? "Kerensky" : "?")
              .contains("Kerensky"));
        String draw = AutoResolveRun.winner(VictoryResult.drawResult(), id -> "nobody");
        assertFalse(draw.startsWith("!"), "the draw line has its text");
        for (Outcome outcome : Outcome.values()) {
            assertFalse(AutoResolveRun.headline(outcome).startsWith("!"), outcome + " has its headline");
        }
    }

    @Test
    void theChatterHasEveryLineOnceInAShuffledOrder() {
        List<String> lines = AutoResolveRun.flavor(new Random(1));
        assertEquals(AutoResolveRun.FLAVOR_LINES, lines.size());
        assertEquals(lines.size(), new HashSet<>(lines).size(), "no line repeats");
        assertFalse(lines.contains(AutoResolveRun.loading()), "the loading line comes first, not among the chatter");
        assertNotEquals(lines, AutoResolveRun.flavor(new Random(2)));
    }
}
