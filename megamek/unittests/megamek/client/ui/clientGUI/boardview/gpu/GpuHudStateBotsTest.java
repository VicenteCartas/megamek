/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import megamek.client.bot.AIType;
import megamek.client.ui.clientGUI.boardview.gpu.GpuHudState.Dialog;
import megamek.common.game.BotStatus;
import org.junit.jupiter.api.Test;

/** The bot utility and the BOT_COMMANDS key: which bot the sheet opens on, and when the players panel opens instead. */
class GpuHudStateBotsTest {

    private final GpuHudState state = new GpuHudState(new GpuPlaybackHistory(new UnitPlayback()));

    @Test
    void theUtilityOpensTheLastBotStillCommandableElseTheFirstAndClosesTheSheet() {
        state.toggleBots(bots(3, 6));
        assertEquals(Dialog.BOTS, state.dialog);
        assertEquals(-1, state.botSheetId, "the sheet shows the first bot");

        state.toggleBot(6);
        state.toggleBots(bots(3, 6));
        assertEquals(Dialog.NONE, state.dialog, "a second press closes the sheet");
        state.toggleBots(bots(3, 6));
        assertEquals(Dialog.BOTS, state.dialog);
        assertEquals(6, state.botSheetId, "it reopens on the bot it showed last");

        state.dialog = Dialog.NONE;
        state.toggleBots(bots(3));
        assertEquals(-1, state.botSheetId, "a bot the player no longer commands falls back to the first");

        state.dialog = Dialog.HELP;
        state.toggleBots(bots(3));
        assertEquals(Dialog.BOTS, state.dialog, "the sheet replaces another dialog");

        state.dialog = Dialog.NONE;
        state.toggleBots(GpuBots.Snapshot.EMPTY);
        assertEquals(Dialog.NONE, state.dialog, "nothing opens without a commandable bot");
    }

    @Test
    void theKeyOpensTheSheetForASingleBotElseThePlayersPanel() {
        state.botCommandsKey(bots(3));
        assertEquals(Dialog.BOTS, state.dialog);
        state.botCommandsKey(bots(3, 6));
        assertEquals(Dialog.NONE, state.dialog, "the key closes an open sheet");

        state.botCommandsKey(bots(3, 6));
        assertEquals(Dialog.PLAYERS, state.dialog, "two bots: the players panel lists both orders rows");
        state.botCommandsKey(bots(3, 6));
        assertEquals(Dialog.NONE, state.dialog);

        state.botCommandsKey(GpuBots.Snapshot.EMPTY);
        assertEquals(Dialog.PLAYERS, state.dialog, "no bot: the players panel, as before");
    }

    private static GpuBots.Snapshot bots(int... ids) {
        List<GpuBots.BotRow> rows = Arrays.stream(ids).mapToObj(id -> new GpuBots.BotRow(id, "Bot " + id,
              AIType.PRINCESS, 0xFF82E2CE, true, 2, false, null, BotStatus.NONE, List.of(), List.of(), Set.of(),
              false)).toList();
        return new GpuBots.Snapshot(rows, List.of(), Map.of());
    }
}
