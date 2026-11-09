/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.gpu.GpuHudState.Dialog;
import megamek.client.ui.util.KeyCommandBind;

/**
 * Menu panel (C.1 G14, C.2, user corrections 3 and 6): the commands of the removed menu bar as the client captures
 * them (File, Game, Board, View, Help, Commands, Maps) in a centred dialog. Its first row opens the players panel and
 * its last row quits to the main menu; groups expand in place; every item shows its shortcut and check mark and runs
 * its own action, except the C.2
 * redirects, which open the native surfaces instead of Swing. Choosing an item closes the panel, as a menu closes.
 */
final class GpuMenuPanel implements GpuHud.Component {
    /** The View-menu items that move the board camera; they need the camera and a board. */
    private static final Set<String> CAMERA = Set.of(ClientGUI.VIEW_TOGGLE_ISOMETRIC, ClientGUI.VIEW_ZOOM_IN,
          ClientGUI.VIEW_ZOOM_OUT, ClientGUI.VIEW_ZOOM_OVERVIEW_TOGGLE);

    private final GpuBoardSource source;
    private final GpuHudState state;
    private final BoardCamera camera;
    private final Table root;
    private final GpuCommandTree tree;
    /** The action command of each item the panel shows, by command id, for the redirects. */
    private final Map<String, String> actions = new HashMap<>();
    private GpuHud.Inputs inputs;

    /** The panel; {@code camera} is the board view's, which the View menu's zoom and Tactical View items move. */
    GpuMenuPanel(GpuHudKit kit, GpuBoardSource source, GpuHudState state, BoardCamera camera) {
        this.source = source;
        this.state = state;
        this.camera = camera;
        root = kit.ui.dialog(text("GpuBoard.hud.menu.title"), () -> state.dialog = Dialog.NONE);
        root.setName("menu-panel");
        tree = new GpuCommandTree(kit.ui, root);
        tree.body.setName("menu-list");
        root.add(tree.body).grow().minHeight(0);
    }

    @Override
    public Actor actor() {
        return root;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        this.inputs = inputs;
        boolean open = state.dialog == Dialog.MENU;
        tree.open(open);
        if (!open) {
            return;
        }
        List<Object> rows = new ArrayList<>();
        BoardScene.Command players = new BoardScene.Command("players", text("GpuBoard.hud.players.title"), "", true,
              false, false, List.of(), () -> state.dialog = Dialog.PLAYERS,
              GpuHintLine.key(inputs.preferences(), KeyCommandBind.BOT_COMMANDS), null);
        tree.rows(List.of(players), "", 0, rows);
        List<BoardScene.Command> menuBar = shown(inputs.frame().globalCommands(), "");
        if (!menuBar.isEmpty()) {
            rows.add(GpuCommandTree.SEPARATOR);
            tree.rows(menuBar, "", 0, rows);
        }
        // The window has no close button in fullscreen, so the panel always offers the way out of the game.
        BoardScene.Command quit = new BoardScene.Command("quit", text("GpuBoard.hud.menu.quit"), "", true, false,
              false, List.of(), source::quitGame, "", null);
        rows.add(GpuCommandTree.SEPARATOR);
        tree.rows(List.of(quit), "", 0, rows);
        tree.show(rows, null, this::run);
    }

    /**
     * The captured menu as the panel shows it (C.2): without the window-positions item, which places Swing windows
     * that no longer show; a redirected toggle checked by its native surface; the isometric item, which the T key's
     * Tactical View replaces here, named after it; the camera items available with a board.
     */
    private List<BoardScene.Command> shown(List<BoardScene.Command> commands, String parent) {
        List<BoardScene.Command> shown = new ArrayList<>();
        for (BoardScene.Command command : commands) {
            String action = actionCommand(command, parent);
            actions.put(command.id(), action);
            if (action.equals(ClientGUI.VIEW_RESET_WINDOW_POSITIONS)) {
                continue;
            }
            String label = action.equals(ClientGUI.VIEW_TOGGLE_ISOMETRIC) ? text("GpuBoard.hud.util.tactical")
                  : command.label();
            boolean enabled = command.enabled() && (!CAMERA.contains(action) || inputs.frame().scene() != null);
            shown.add(new BoardScene.Command(command.id(), label, command.detail(), enabled, command.commit(),
                  command.boardTool(), shown(command.children(), command.id()), command.action(), command.shortcut(),
                  command.selected() == null ? null : checked(action, command.selected())));
        }
        return shown;
    }

    /** A redirected toggle's check mark shows its native surface; any other toggle keeps MegaMek's state. */
    private boolean checked(String action, boolean selected) {
        return switch (action) {
            case ClientGUI.VIEW_UNIT_DISPLAY -> GpuRecordSheet.open(state);
            case ClientGUI.VIEW_FORCE_DISPLAY -> state.overview;
            case ClientGUI.VIEW_KEYBINDS_OVERLAY -> state.dialog == Dialog.HELP;
            case ClientGUI.VIEW_PLAYER_LIST -> state.dialog == Dialog.PLAYERS;
            case ClientGUI.VIEW_ROUND_REPORT, ClientGUI.VIEW_ROUNDS_IN_AIR -> state.logOpen();
            case ClientGUI.VIEW_TOGGLE_ISOMETRIC -> inputs.view().tactical();
            default -> selected;
        };
    }

    /**
     * Chooses an item: the panel closes, then a C.2 redirect opens or toggles its native surface, and every other item
     * runs its own action (on the Swing thread, rechecked there).
     */
    private void run(BoardScene.Command command) {
        state.dialog = Dialog.NONE;
        BoardScene scene = inputs.frame().scene();
        switch (actions.getOrDefault(command.id(), "")) {
            case ClientGUI.VIEW_UNIT_DISPLAY -> {
                if (GpuRecordSheet.open(state)) {
                    state.recordOpen = false;
                } else {
                    GpuRecordSheet.show(state, state.sheetTab);
                }
            }
            case ClientGUI.VIEW_FORCE_DISPLAY -> state.overview = !state.overview;
            case ClientGUI.VIEW_KEYBINDS_OVERLAY -> state.dialog = Dialog.HELP;
            case ClientGUI.VIEW_PLAYER_LIST -> state.dialog = Dialog.PLAYERS;
            case ClientGUI.VIEW_ROUND_REPORT -> state.toggleLog();
            case ClientGUI.VIEW_ROUNDS_IN_AIR -> {
                // The log's Summary lists the artillery in flight.
                if (!state.logOpen()) {
                    state.toggleLog();
                }
            }
            case ClientGUI.VIEW_TOGGLE_ISOMETRIC -> camera.setTactical(!camera.tactical(), scene);
            case ClientGUI.VIEW_ZOOM_IN -> camera.zoom(1 / GpuBattleView.ZOOM_STEP);
            case ClientGUI.VIEW_ZOOM_OUT -> camera.zoom(GpuBattleView.ZOOM_STEP);
            case ClientGUI.VIEW_ZOOM_OVERVIEW_TOGGLE -> camera.toggleOverview(scene);
            default -> command.action().run();
        }
    }

    /**
     * A menu item's action command, from its own menu key ("{action command}:{text}", GpuBoardActions.menuKey): its id
     * is the key, after its group's id and a '/' when the group's id begins it. A text may hold '/' or ':' ("Ruler /
     * LOS Tool"), so the key is never split at either. "" for the panel's own row and the groups that the source adds.
     */
    private static String actionCommand(BoardScene.Command command, String parent) {
        String id = command.id();
        String key = !parent.isEmpty() && id.startsWith(parent + "/") ? id.substring(parent.length() + 1) : id;
        int colon = key.indexOf(':');
        return colon < 0 ? "" : key.substring(0, colon);
    }
}
