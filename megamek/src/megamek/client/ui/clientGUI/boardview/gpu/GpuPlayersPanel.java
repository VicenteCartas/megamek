/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.text;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.clientGUI.boardview.gpu.GpuHudState.Dialog;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.Player;

/**
 * Players panel (C.1 G14, plan A.15 O3, swing-inventory W3 and W4): the players as the Swing player list shows them,
 * and under each bot the local player commands, an Orders and behavior row that opens the bot sheet on it; the bot
 * commands panel's Pause/Continue button first. Opened by the Menu, the View menu's player list and BOT_COMMANDS with
 * more than one bot.
 */
final class GpuPlayersPanel implements GpuHud.Component {
    /** The player colour's square before the name. */
    private static final float SWATCH = 10;

    /** A player as the panel shows them: name, colour, whether local, the done state and the other marks. */
    private record Shown(String name, int rgb, boolean local, String state, boolean done, String marks) { }

    private final UiKit ui;
    private final GpuBoardSource source;
    private final GpuHudState state;
    private final Table root;
    private final GpuCommandTree tree;
    /** The open state last passed to the players service, which captures Pause/Continue only while it holds. */
    private boolean sentOpen;

    GpuPlayersPanel(GpuHudKit kit, GpuBoardSource source, GpuHudState state) {
        ui = kit.ui;
        this.source = source;
        this.state = state;
        root = ui.dialog(text("GpuBoard.hud.players.title"), () -> state.dialog = Dialog.NONE);
        root.setName("players-panel");
        tree = new GpuCommandTree(ui, root);
        tree.body.setName("players-list");
        root.add(tree.body).grow().minHeight(0);
    }

    @Override
    public Actor actor() {
        return root;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        boolean open = state.dialog == Dialog.PLAYERS;
        if (open != sentOpen) {
            sentOpen = open;
            source.players().setPanelOpen(open);
        }
        tree.open(open);
        if (!open) {
            return;
        }
        GpuPlayers.Snapshot players = inputs.frame().panels().players();
        GpuBots.Snapshot bots = inputs.frame().panels().bots();
        List<Object> rows = new ArrayList<>();
        if (players.pause() != null) {
            tree.rows(List.of(players.pause()), "", 0, rows);
        }
        for (GpuPlayers.PlayerRow player : players.players()) {
            if (!rows.isEmpty()) {
                rows.add(GpuCommandTree.SEPARATOR);
            }
            rows.add(shown(player));
            if (bots.bot(player.id()) != null) {
                tree.rows(List.of(orders(player.id())), "bot " + player.id(), 1, rows);
            }
        }
        tree.show(rows, row -> player((Shown) row), command -> {
            // As a menu closes; a bot's orders row then opens the bot sheet.
            state.dialog = Dialog.NONE;
            command.action().run();
        });
    }

    /** The row under a bot that opens the bot sheet on it. */
    private BoardScene.Command orders(int botId) {
        return new BoardScene.Command("orders", text("GpuBoard.hud.players.botOrders"),
              text("GpuBoard.hud.players.botOrders.tip"), true, false, List.of(), () -> state.toggleBot(botId));
    }

    /**
     * What the Swing player list shows of a player: the team (Lone Wolf, Unassigned), Done or Waiting unless an
     * observer or ghost, and the marks bot, observer, ghost, GM, see all, single blind and ignores double blind.
     */
    private static Shown shown(GpuPlayers.PlayerRow player) {
        List<String> marks = new ArrayList<>();
        marks.add(player.team() == Player.TEAM_NONE ? text("GpuBoard.hud.players.loneWolf")
              : player.team() == Player.TEAM_UNASSIGNED ? text("GpuBoard.hud.players.unassigned")
              : text("GpuBoard.hud.players.team", player.team()));
        mark(marks, player.bot(), "GpuBoard.hud.players.bot");
        mark(marks, player.observer(), "GpuBoard.hud.players.observer");
        mark(marks, player.ghost(), "GpuBoard.hud.players.ghost");
        mark(marks, player.gameMaster(), "GpuBoard.hud.players.gameMaster");
        mark(marks, player.seeAll(), "GpuBoard.hud.players.seeAll");
        mark(marks, player.singleBlind(), "GpuBoard.hud.players.singleBlind");
        mark(marks, player.ignoresDoubleBlind(), "GpuBoard.hud.players.ignoreDoubleBlind");
        String done = player.observer() || player.ghost() ? ""
              : text(player.done() ? "GpuBoard.hud.players.done" : "GpuBoard.hud.players.waiting");
        return new Shown(player.name(), player.rgb(), player.local(), done, player.done(), String.join(" · ", marks));
    }

    private static void mark(List<String> marks, boolean shown, String key) {
        if (shown) {
            marks.add(text(key));
        }
    }

    /** A player's row: the colour square and the name (mint for the local player), the done chip, and the marks. */
    private Actor player(Shown player) {
        Table row = new Table();
        row.pad(8, 14, 6, 14);
        Color colour = new Color();
        Color.argb8888ToColor(colour, player.rgb());
        row.add(new Image(ui.skin.newDrawable("white", colour))).size(SWATCH).padRight(10);
        Label name = ui.label(player.name(), "hud-medium", 13, player.local() ? UiTheme.MINT : UiTheme.TEXT);
        name.setEllipsis(true);
        row.add(name).growX().minWidth(0).left();
        if (!player.state().isEmpty()) {
            row.add(ui.chip(player.state(), player.done() ? UiKit.Tone.OK : UiKit.Tone.NORMAL)).padLeft(10);
        }
        row.row();
        row.add();
        Label marks = ui.label(player.marks(), "hud-small", 11.5f, UiTheme.MUTED);
        marks.setWrap(true);
        row.add(marks).colspan(2).growX().left().padTop(2);
        return row;
    }
}
