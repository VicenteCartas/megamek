/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import megamek.client.Client;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ChatterBox;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogAnswer;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogKind;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRequest;
import megamek.client.ui.clientGUI.boardview.overlay.ChatterBoxOverlay;
import megamek.client.ui.clientGUI.boardview.overlay.ToastLevel;
import megamek.common.Player;
import megamek.common.ResolvedAttack;
import megamek.common.enums.GamePhase;
import megamek.common.event.player.GamePlayerChatEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** The chat, toast and players services: what they keep of the client's messages and how they send. */
@ExtendWith(GpuTestSupport.StagedData.class)
@Timeout(120)
class GpuHudMessagesTest {

    @Test
    void chatLinesKeepTheSenderAndTurnEveryOtherLineIntoASystemLine() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                chat(fixture, "GPU review: Moving Alpha to the ridge");
                chat(fixture, "***Server: Opponent has connected.");
                // ClientGUI.systemMessage's local notice
                chat(fixture, "\nMegaMek: Game saved to quicksave");
            });
            List<GpuChat.ChatLine> lines = lines(fixture);
            assertEquals(List.of("GPU review", "System", "System"),
                  lines.stream().map(GpuChat.ChatLine::sender).toList());
            assertEquals(List.of("Moving Alpha to the ridge", "Opponent has connected.",
                  "MegaMek: Game saved to quicksave"), lines.stream().map(GpuChat.ChatLine::text).toList());
            assertEquals(List.of(false, true, true), lines.stream().map(GpuChat.ChatLine::system).toList());
            assertEquals(0, lines.get(1).rgb(), "System lines carry no player colour");
            assertEquals(List.of(1L, 2L, 3L), lines.stream().map(GpuChat.ChatLine::id).toList());

            SwingUtilities.invokeAndWait(() -> {
                for (int line = 1; line <= 80; line++) {
                    chat(fixture, "GPU review: line " + line);
                }
            });
            lines = lines(fixture);
            assertEquals(80, lines.size(), "The panel keeps the 80 newest lines");
            assertEquals("line 1", lines.getFirst().text());
            assertEquals("line 80", lines.getLast().text());
        }
    }

    @Test
    void sendingFromTheHudUsesTheBoardChatAndKeepsTheHistory() throws Exception {
        Client client = mock(Client.class);
        ChatterBox chatterBox = mock(ChatterBox.class);
        chatterBox.history = new LinkedList<>(List.of("earlier"));
        ClientGUI gui = mock(ClientGUI.class);
        when(gui.getClient()).thenReturn(client);
        when(gui.getMainPanel()).thenReturn(new JPanel());
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            when(client.getGame()).thenReturn(fixture.game);
            ChatterBoxOverlay overlay = GpuTestSupport.onSwing(() -> {
                ChatterBoxOverlay board = new ChatterBoxOverlay(gui, fixture.view, null, chatterBox);
                fixture.view.addOverlay(board);
                return board;
            });
            try {
                fixture.source.chat().send("Moving Alpha to the ridge");
                fixture.source.chat().send("   ");
                SwingUtilities.invokeAndWait(() -> { });

                verify(client).sendChat("Moving Alpha to the ridge");
                verify(client, never()).sendChat("   ");
                assertEquals(List.of("Moving Alpha to the ridge", "earlier"), chatterBox.history);

                // A chat line is not board input: it is sent while a native ask holds the board input back.
                FutureTask<DialogAnswer> ask = new FutureTask<>(() -> fixture.source.ask(new DialogRequest(0,
                      DialogKind.MESSAGE, "Confirm", "Continue?", false, List.of("Yes", "No"), 0, 1, List.of(),
                      List.of(), "", false, "", null, null, List.of())));
                SwingUtilities.invokeLater(ask);
                DialogRequest shown = awaitDialog(fixture.source);
                assertFalse(GpuTestSupport.onSwing(fixture.source::acceptsInput));
                fixture.source.chat().send("Holding at the ridge");
                SwingUtilities.invokeAndWait(() -> { });
                verify(client).sendChat("Holding at the ridge");
                fixture.source.answer(shown.id(), DialogAnswer.cancelled(shown));
                ask.get(30, TimeUnit.SECONDS);
            } finally {
                SwingUtilities.invokeAndWait(() -> {
                    fixture.view.removeOverlay(overlay);
                    overlay.dispose();
                    GUIPreferences.getInstance().removePreferenceChangeListener(overlay);
                });
            }
        }
    }

    @Test
    void theRoundLinesFollowEachInitiativeResultAndTheRoundsWeaponHits() throws Exception {
        List<GpuBattleStatus.InitiativeSide> won = List.of(side("Princess", GpuBattleStatus.Side.ENEMY, 7, List.of()),
              side("GPU review", GpuBattleStatus.Side.OWN, 9, List.of()));
        // A Tactical Genius reroll replaces the sides within the same round and report phase.
        List<GpuBattleStatus.InitiativeSide> rerolled = List.of(
              side("GPU review", GpuBattleStatus.Side.OWN, 6, List.of()),
              side("Princess", GpuBattleStatus.Side.ENEMY, 10, List.of()));
        GpuReportLog.Snapshot reports = mock(GpuReportLog.Snapshot.class);
        when(reports.combat()).thenReturn(List.of(shot(3, ResolvedAttack.Kind.SHOT, true),
              shot(3, ResolvedAttack.Kind.SHOT, true), shot(3, ResolvedAttack.Kind.SHOT, false),
              shot(3, ResolvedAttack.Kind.PUNCH, true), shot(2, ResolvedAttack.Kind.SHOT, true)));
        GpuBattleStatus.Snapshot tieBroken = status(5, GamePhase.INITIATIVE_REPORT,
              List.of(side("GPU review", GpuBattleStatus.Side.OWN, 8, List.of(5)),
                    side("Princess", GpuBattleStatus.Side.ENEMY, 8, List.of(9))));
        // Two sides that share the best rolls have no winner, as on the initiative card: no line.
        GpuBattleStatus.Snapshot tied = status(6, GamePhase.INITIATIVE_REPORT,
              List.of(side("GPU review", GpuBattleStatus.Side.OWN, 8, List.of()),
                    side("Princess", GpuBattleStatus.Side.ENEMY, 8, List.of())));
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            GpuChat chat = fixture.source.chat();
            SwingUtilities.invokeAndWait(() -> {
                chat.roundLines(status(3, GamePhase.INITIATIVE_REPORT, won), reports);
                chat.roundLines(status(3, GamePhase.INITIATIVE_REPORT, won), reports);
                chat.roundLines(status(3, GamePhase.INITIATIVE_REPORT, rerolled), reports);
                chat.roundLines(status(3, GamePhase.INITIATIVE_REPORT, rerolled), reports);
                chat.roundLines(status(3, GamePhase.END_REPORT, rerolled), reports);
                chat.roundLines(status(3, GamePhase.END_REPORT, rerolled), reports);
                // The next round's rolls may equal the last ones; the round is new, so is its line.
                chat.roundLines(status(4, GamePhase.INITIATIVE_REPORT, rerolled), reports);
                chat.roundLines(tieBroken, reports);
                chat.roundLines(tied, reports);
            });
            List<GpuChat.ChatLine> lines = lines(fixture);
            assertEquals(List.of(Messages.getString("GpuBoard.hud.chat.initiativeLineOwn", 3, 9, "7", "GPU review"),
                  "Round 3 · initiative 6 vs 10 · Princess wins", "Round 3 resolved · 2 hits",
                  "Round 4 · initiative 6 vs 10 · Princess wins", "Round 5 · initiative 8 vs 8 · Princess wins"),
                  lines.stream().map(GpuChat.ChatLine::text).toList());
            assertEquals(List.of(true, true, true, true, true),
                  lines.stream().map(GpuChat.ChatLine::system).toList());
        }
    }

    @Test
    void toastsKeepTheirLevelAndDurationAndTheStackDepth() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        int duration = preferences.getToastDurationSeconds();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                preferences.setToastDurationSeconds(3);
                GpuToasts toasts = fixture.source.toasts();
                toasts.add(ToastLevel.INFO, "Dropped from the full stack", null);
                toasts.add(ToastLevel.SUCCESS, "Order sent", null);
                toasts.add(ToastLevel.WARNING, "Sensor contact", null);
                toasts.add(ToastLevel.ERROR, "Invalid deployment", fixture.entity);
                toasts.add(ToastLevel.GAMEMASTER, "Unit edited", null);
                toasts.add(ToastLevel.INFO, "Bridge dismantled", null);
            });
            List<GpuToasts.Toast> shown = GpuTestSupport.onSwing(() -> fixture.source.toasts().capture().toasts());
            assertEquals(List.of(ToastLevel.SUCCESS, ToastLevel.WARNING, ToastLevel.ERROR, ToastLevel.GAMEMASTER,
                  ToastLevel.INFO), shown.stream().map(GpuToasts.Toast::level).toList());
            assertEquals(List.of(3000, 4000, 5000, 4000, 3000),
                  shown.stream().map(GpuToasts.Toast::durationMs).toList());
            assertEquals(List.of(2L, 3L, 4L, 5L, 6L), shown.stream().map(GpuToasts.Toast::id).toList());
            assertNotNull(shown.get(2).icon(), "An own unit's toast keeps its artwork");
            assertEquals(List.of(56, 48), List.of(shown.get(2).icon().width(), shown.get(2).icon().height()),
                  "The upright unit icon of the Swing toast, not the board sprite");
            assertNull(shown.get(1).icon());
        } finally {
            SwingUtilities.invokeAndWait(() -> preferences.setToastDurationSeconds(duration));
        }
    }

    @Test
    void aHudToastGoesThroughTheClientsOneToastEntryPoint() throws Exception {
        ClientGUI gui = mock(ClientGUI.class);
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            BoardClientState view = spy(fixture.view);
            doReturn(gui).when(view).getClientgui();
            GpuBoardSource source = GpuTestSupport.onSwing(() -> new GpuBoardSource(view, () -> fixture.panel));
            try {
                source.toasts().post(ToastLevel.WARNING, "Sensor contact - no visual identification yet");
                SwingUtilities.invokeAndWait(() -> { });
                verify(gui).addToast(ToastLevel.WARNING, "Sensor contact - no visual identification yet");
                assertEquals(List.of(), GpuTestSupport.onSwing(() -> source.toasts().capture().toasts()),
                      "The HUD shows it only once ClientGUI hands it back");
            } finally {
                SwingUtilities.invokeAndWait(source::close);
            }
        }
    }

    @Test
    void playersAreListedInIdOrderWithTeamAndTheStatesOfTheSwingPlayerList() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                Player bot = new Player(3, "Princess");
                bot.setTeam(2);
                bot.setBot(true);
                bot.setDone(true);
                bot.setSingleBlind(true);
                fixture.game.addPlayer(bot.getId(), bot);
                Player ally = new Player(2, "Ally");
                ally.setTeam(1);
                ally.setObserver(true);
                fixture.game.addPlayer(ally.getId(), ally);
                Player referee = new Player(4, "Referee");
                referee.setTeam(Player.TEAM_NONE);
                referee.setGameMaster(true);
                referee.setGhost(true);
                referee.setSeeAll(true);
                fixture.game.addPlayer(referee.getId(), referee);
            });
            GpuPlayers.Snapshot players = GpuTestSupport.onSwing(() -> fixture.source.players().capture());
            List<GpuPlayers.PlayerRow> rows = players.players();
            assertEquals(List.of("0 GPU review team 1 local", "2 Ally team 1 observer",
                  "3 Princess team 2 done bot singleBlind ignoresDoubleBlind",
                  "4 Referee team 0 ghost gm seeAll ignoresDoubleBlind"), rows.stream().map(row -> row.id() + " "
                        + row.name() + " team " + row.team() + (row.local() ? " local" : "")
                        + (row.done() ? " done" : "") + (row.bot() ? " bot" : "") + (row.observer() ? " observer" : "")
                        + (row.ghost() ? " ghost" : "") + (row.gameMaster() ? " gm" : "")
                        + (row.seeAll() ? " seeAll" : "") + (row.singleBlind() ? " singleBlind" : "")
                        + (row.ignoresDoubleBlind() ? " ignoresDoubleBlind" : "")).toList());
            assertSame(players, GpuTestSupport.onSwing(() -> fixture.source.players().capture()),
                  "Unchanged players keep the instance");
        }
    }

    /** Polls the published dialog as the GL thread does every frame. */
    private static DialogRequest awaitDialog(GpuBoardSource source) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (source.dialog() == null && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertNotNull(source.dialog(), "The native ask is shown");
        return source.dialog();
    }

    private static List<GpuChat.ChatLine> lines(GpuBoardFixture fixture) throws Exception {
        return GpuTestSupport.onSwing(() -> fixture.source.chat().capture().lines());
    }

    private static void chat(GpuBoardFixture fixture, String message) {
        fixture.game.fireGameEvent(new GamePlayerChatEvent(fixture, null, message));
    }

    private static GpuBattleStatus.InitiativeSide side(String name, GpuBattleStatus.Side side, int total,
          List<Integer> tieBreaks) {
        return new GpuBattleStatus.InitiativeSide(name, 0, side, total, total, 0, List.of(), tieBreaks, List.of());
    }

    /** A status of the given round, phase and initiative; mocked so the test does not depend on its other fields. */
    private static GpuBattleStatus.Snapshot status(int round, GamePhase phase,
          List<GpuBattleStatus.InitiativeSide> initiative) {
        GpuBattleStatus.Snapshot status = mock(GpuBattleStatus.Snapshot.class);
        when(status.round()).thenReturn(round);
        when(status.phase()).thenReturn(phase);
        when(status.initiative()).thenReturn(initiative);
        return status;
    }

    private static GpuReportLog.CombatEvent shot(int round, ResolvedAttack.Kind kind, boolean hit) {
        return new GpuReportLog.CombatEvent(UUID.randomUUID(), round, GamePhase.FIRING_REPORT, kind, 1, 2, "", "",
              hit, hit ? 5 : 0, List.of(), null, 0);
    }
}
