/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import javax.swing.SwingUtilities;

import megamek.client.ui.Messages;
import megamek.common.Player;
import megamek.common.Report;
import megamek.common.enums.GamePhase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The source's capture writes the chat's two round lines (plan O6, I1a acceptance A6) from the status and the report
 * log it captures: the initiative result once per round while the initiative report shows two sides, and the round's
 * weapon hits once when the end report starts, however often the client captures in between.
 */
@ExtendWith(GpuTestSupport.StagedData.class)
@Timeout(120)
class GpuChatRoundLinesTest {
    /** The server's initiative line "{name} rolls a {roll}." (TWGameManager.writeInitiativeReport). */
    private static final int INITIATIVE_REPORT = 1015;

    @Test
    void theCaptureWritesTheInitiativeAndResolvedLinesOncePerRound() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                Player opponent = new Player(1, "Opposing force");
                opponent.setTeam(2);
                fixture.game.addPlayer(opponent.getId(), opponent);
                // The game keeps its reports by round from the first on.
                fixture.game.setRoundCount(1);
                // The rolls as InitiativeRoll writes them: total[roll+bonus].
                fixture.game.addReports(List.of(
                      new Report(INITIATIVE_REPORT).add(fixture.player.getName()).add("9[7+2]"),
                      new Report(INITIATIVE_REPORT).add(opponent.getName()).add("7[7+0]")));
                fixture.game.setPhase(GamePhase.INITIATIVE_REPORT);
                fixture.source.refresh();
                fixture.source.refresh();
                fixture.game.setPhase(GamePhase.END_REPORT);
                fixture.source.refresh();
                fixture.source.refresh();
            });
            List<String> lines = fixture.source.takeFrame().panels().chat().lines().stream()
                  .map(GpuChat.ChatLine::text).toList();
            assertEquals(List.of(Messages.getString("GpuBoard.hud.chat.initiativeLineOwn", 1, 9, "7", "GPU review"),
                  Messages.getString("GpuBoard.hud.chat.resolvedLine", 1, 0L)), lines);
        }
    }
}
