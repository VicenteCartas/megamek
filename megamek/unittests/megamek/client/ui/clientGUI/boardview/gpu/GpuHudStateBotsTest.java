/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import megamek.client.bot.AIType;
import megamek.client.ui.clientGUI.boardview.gpu.GpuHudState.Dialog;
import megamek.common.game.BotStatus;
import megamek.common.units.Entity;
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

    @Test
    void onlyAnAlliedCasparLanceIsSelectedAndAnInspectionEndsIt() {
        GpuBots.Lance alpha = new GpuBots.Lance(4, "Alpha", List.of(20, 21), 20, "Route");
        GpuBots.Order order = new GpuBots.Order(4, "Route", false, true);
        state.bots(new GpuBots.Snapshot(List.of(bot(3, AIType.PRINCESS, alpha, order),
              bot(6, AIType.CASPAR, alpha, order)), List.of(), Map.of()));

        state.selectLance(3, 4);
        assertNull(state.selectedLance(), "Princess takes orders for single units only");
        state.selectLance(6, 4);
        assertEquals(alpha, state.selectedLance());
        assertEquals(6, state.selectedLanceBot());
        assertEquals(Entity.NONE, state.ownCardUnit(), "the lance card takes the left slot");

        state.inspected = 21;
        assertNull(state.selectedLance(), "inspecting a unit ends the lance selection");
        assertEquals(21, state.ownCardUnit(), "an allied bot's unit shows in the left card");
        assertEquals(Entity.NONE, state.otherCardUnit(), "never in the right card");

        state.inspected = Entity.NONE;
        assertNull(state.selectedLance(), "the ended selection does not come back");
        state.selectLance(6, 4);
        state.clearLance();
        assertNull(state.selectedLance());
    }

    private static GpuBots.BotRow bot(int id, AIType type, GpuBots.Lance lance, GpuBots.Order order) {
        Map<Integer, GpuBots.Order> orders = Map.of(20, order, 21, order);
        return new GpuBots.BotRow(id, "Bot " + id, type, 0xFF82E2CE, true, 2, false, null, BotStatus.NONE,
              List.of(), List.of(), Set.of(), false, List.of(lance), orders);
    }

    private static GpuBots.Snapshot bots(int... ids) {
        List<GpuBots.BotRow> rows = Arrays.stream(ids).mapToObj(id -> new GpuBots.BotRow(id, "Bot " + id,
              AIType.PRINCESS, 0xFF82E2CE, true, 2, false, null, BotStatus.NONE, List.of(), List.of(), Set.of(),
              false)).toList();
        return new GpuBots.Snapshot(rows, List.of(), Map.of());
    }
}
