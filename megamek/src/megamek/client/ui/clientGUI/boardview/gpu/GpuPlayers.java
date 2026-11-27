/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.Comparator;
import java.util.List;

import megamek.client.ui.clientGUI.boardview.BoardHexPick;
import megamek.common.Player;

/**
 * EDT service for the players panel: the game's players with the states the Swing player list shows, the bot commands
 * panel's Pause/Continue while the panel is open, and the board's hex pick while it runs. The bots' orders are the bot
 * sheet's (GpuBots).
 */
final class GpuPlayers {
    /**
     * The board's hex pick (a bot order's targets, or the Move Order editor's waypoints) while it runs: its
     * instructions, and its status once a hex is picked ("" before). The board's left clicks pick hexes; the Done key
     * ends it as its Done does (a bot order goes out), Esc as its Cancel does.
     */
    record Pick(String instructions, String status) { }

    /**
     * One player. {@code team} is the player's team number, {@link Player#TEAM_NONE} (the Swing list's "Lone Wolf")
     * or {@link Player#TEAM_UNASSIGNED}. {@code seeAll}, {@code singleBlind} and {@code ignoresDoubleBlind} are the
     * visibility modes the Swing list marks.
     */
    record PlayerRow(int id, String name, int team, int rgb, boolean local, boolean done, boolean bot,
          boolean observer, boolean ghost, boolean gameMaster, boolean seeAll, boolean singleBlind,
          boolean ignoresDoubleBlind) { }

    /**
     * The players in id order, the bot commands panel's Pause/Continue button as a plain command while the players
     * panel is open and a bot plays (null otherwise), and the hex pick that runs (null for none).
     */
    record Snapshot(List<PlayerRow> players, BoardScene.Command pause, Pick pick) {
        static final Snapshot EMPTY = new Snapshot(List.of());

        Snapshot {
            players = List.copyOf(players);
        }

        Snapshot(List<PlayerRow> players) {
            this(players, null);
        }

        Snapshot(List<PlayerRow> players, BoardScene.Command pause) {
            this(players, pause, null);
        }
    }

    private final GpuBoardSource source;
    // GL thread writes, EDT reads at the next capture, as GpuUnitRecord's sheet flag.
    private volatile boolean panelOpen;
    private Snapshot snapshot = Snapshot.EMPTY;

    GpuPlayers(GpuBoardSource source) {
        this.source = source;
    }

    /**
     * GL thread: whether the players panel is open. Only then does the capture build the Pause/Continue command; the
     * next capture follows the change.
     */
    void setPanelOpen(boolean open) {
        panelOpen = open;
    }

    /**
     * EDT: the players of the source's game in id order, as the Swing player list shows them; the previous instance
     * while nothing changed. Each capture builds a new Pause/Continue action, so the command keeps its previous
     * instance while it shows the same.
     */
    Snapshot capture() {
        GpuBoardSource.requireSwingThread();
        var view = source.currentView();
        Player local = view.getLocalPlayer();
        // Pause/Continue does not depend on the shown board; it waits only for the source to accept input.
        GpuBoardActions actions = panelOpen
              ? new GpuBoardActions(view, () -> null, () -> !source.acceptsInput(), source::refresh) : null;
        List<PlayerRow> rows = view.game.getPlayersList().stream()
              .sorted(Comparator.comparingInt(Player::getId))
              .map(player -> new PlayerRow(player.getId(), player.getName(), player.getTeam(),
                    player.getColour().getColour().getRGB(), local != null && local.getId() == player.getId(),
                    player.isDone(), player.isBot(), player.isObserver(), player.isGhost(), player.isGameMaster(),
                    player.getSeeAll(), player.getSingleBlind(), player.canIgnoreDoubleBlind()))
              .toList();
        BoardScene.Command pause = actions == null || rows.stream().noneMatch(PlayerRow::bot) ? null
              : actions.pauseCommand();
        if (pause != null && snapshot.pause() != null) {
            pause = unchanged(List.of(pause), List.of(snapshot.pause())).getFirst();
        }
        BoardHexPick picker = picker();
        Pick pick = picker == null ? null
              : new Pick(picker.instructions(), picker.hasHexes() ? picker.status() : "");
        Snapshot next = new Snapshot(rows, pause, pick);
        if (!next.equals(snapshot)) {
            snapshot = next;
        }
        return snapshot;
    }

    /** GL thread: ends the hex pick that runs, as its Done ({@code send}: the order goes out) or Cancel does. */
    void endPick(boolean send) {
        source.command(() -> {
            BoardHexPick picker = picker();
            if (picker != null && send) {
                picker.done();
            } else if (picker != null) {
                picker.cancel();
            }
        });
    }

    /** EDT: the closing window cancels the hex pick, whose Done and Cancel it showed. */
    void close() {
        BoardHexPick picker = picker();
        if (picker != null) {
            picker.cancel();
        }
    }

    /** EDT: the board's hex pick while it runs (a bot order's, or the Move Order editor's), else null. */
    private BoardHexPick picker() {
        return source.currentView().getHexPick();
    }

    /** {@code previous} while it shows what {@code next} shows, else {@code next}. */
    private static List<BoardScene.Command> unchanged(List<BoardScene.Command> next,
          List<BoardScene.Command> previous) {
        return shown(next).equals(shown(previous)) ? previous : next;
    }

    /** The commands without their actions, which are new on every capture: what the panel shows of them. */
    private static List<BoardScene.Command> shown(List<BoardScene.Command> commands) {
        return commands.stream().map(command -> new BoardScene.Command(command.id(),
              command.label(), command.detail(), command.enabled(), command.commit(), command.boardTool(),
              shown(command.children()), null, command.shortcut(), command.selected())).toList();
    }
}
