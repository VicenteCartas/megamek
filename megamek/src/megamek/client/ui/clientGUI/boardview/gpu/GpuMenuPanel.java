/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.gpu.GpuGameMenu.Entry;
import megamek.client.ui.clientGUI.boardview.gpu.GpuHudState.Dialog;
import megamek.client.ui.util.KeyCommandBind;

/**
 * The battle's game menu, which Esc opens: the lobby's menu ({@link GpuGameMenu}) in its token dialog card. Its first
 * page holds what a player looks for in a pause menu (Resume, saving, the Players panel, Settings, Return to Lobby and
 * Quit) and opens the rest of the commands the removed menu bar held as pages: Game (the game's settings and its
 * file and unit commands), Display (the board overlays the HUD has no control of its own for), Commands (the game
 * commands), Maps (with more than one map) and Help. The menu bar's items that the HUD shows or does itself (the unit
 * display, minimap, log, forces overview, camera, the board's hex and field-of-view overlays) are left out, as are its
 * cache and image commands. Each item runs the menu bar's own action, so its availability and check mark stay
 * MegaMek's.
 */
final class GpuMenuPanel implements GpuHud.Component {
    private static final String SAVE = "Lobby.gameMenu.save";
    private static final String QUICK_SAVE = "Lobby.gameMenu.quickSave";
    private static final String GAME = "GpuBoard.hud.menu.game";
    private static final String DISPLAY = "GpuBoard.hud.menu.display";
    private static final String COMMANDS = "GameCommands.title";
    private static final String MAPS = "GpuBoard.hud.menu.maps";
    private static final String HELP = "Lobby.menu.help";
    /** The Game page's items by group, as the menu bar's action commands. */
    private static final List<List<String>> GAME_ITEMS = List.of(
          List.of(ClientGUI.VIEW_GAME_OPTIONS, ClientGUI.VIEW_PLAYER_SETTINGS, ClientGUI.FILE_GAME_EDIT_BOTS,
                ClientGUI.VIEW_NETWORK_INFORMATION),
          List.of(ClientGUI.FILE_UNITS_REINFORCE, ClientGUI.FILE_UNITS_REINFORCE_RAT, ClientGUI.FILE_UNITS_SAVE,
                ClientGUI.FIRE_SAVE_WEAPON_ORDER),
          List.of(ClientGUI.GAME_ALL_MG_BURST_ON, ClientGUI.GAME_ALL_MG_BURST_OFF, ClientGUI.GAME_ALL_HOT_LOAD_ON,
                ClientGUI.GAME_ALL_HOT_LOAD_OFF),
          List.of(ClientGUI.FILE_GAME_SAVE_SERVER, ClientGUI.BOARD_SAVE_AS, ClientGUI.VIEW_NOVA_NETWORKS));
    /** The Display page's items by group: the overlays and labels the HUD has no control of its own for. */
    private static final List<List<String>> DISPLAY_ITEMS = List.of(
          List.of(ClientGUI.VIEW_TURN_DETAILS_OVERLAY, ClientGUI.VIEW_TOGGLE_SHOW_OBJECTS,
                ClientGUI.VIEW_TOGGLE_FOV_SPOTTING, ClientGUI.VIEW_TOGGLE_FLEE_ZONE,
                ClientGUI.VIEW_TOGGLE_FIRING_SOLUTIONS, ClientGUI.VIEW_TOGGLE_CF_WARNING),
          List.of(ClientGUI.VIEW_LABELS, ClientGUI.VIEW_MOVE_MOD_ENV));

    private final GpuBoardSource source;
    private final GpuHudState state;
    private final Table root;
    private final GpuGameMenu menu;
    private boolean open;

    /** The panel over the board of {@code source}. */
    GpuMenuPanel(GpuHudKit kit, GpuBoardSource source, GpuHudState state) {
        this.source = source;
        this.state = state;
        Runnable close = () -> state.dialog = Dialog.NONE;
        root = kit.ui.dialog(text("GpuBoard.hud.menu.title"), close, true);
        root.setName("menu-panel");
        menu = new GpuGameMenu(kit.ui, close);
        menu.actor().top();
        ScrollPane list = kit.ui.scrollList(menu.actor(), true, false);
        list.setName("menu-list");
        list.setFadeScrollBars(false);
        list.setScrollingDisabled(true, false);
        root.add(list).grow().minHeight(0).row();
    }

    @Override
    public Actor actor() {
        return root;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        boolean shown = state.dialog == Dialog.MENU;
        if (shown != open) {
            open = shown;
            if (open) {
                menu.open(groups(inputs));
            } else {
                Stage stage = root.getStage();
                if (stage != null && stage.getKeyboardFocus() != null
                      && stage.getKeyboardFocus().isDescendantOf(root)) {
                    stage.setKeyboardFocus(null);
                }
            }
            return;
        }
        if (open) {
            menu.show(groups(inputs));
        }
    }

    /** Esc on the open menu: goes back a page; false on the first page, where Esc closes the menu. */
    boolean back() {
        return open && menu.back();
    }

    /** The first page's groups, with the pages they open, from the menu bar as the client captured it. */
    private List<List<Entry>> groups(GpuHud.Inputs inputs) {
        Map<String, BoardScene.Command> items = new HashMap<>();
        BoardScene.Command commands = null;
        BoardScene.Command maps = null;
        for (BoardScene.Command group : inputs.frame().globalCommands()) {
            if (group.id().equals(GpuBoardActions.GAME_COMMANDS_ID)) {
                commands = group;
            } else if (group.id().equals("boards")) {
                maps = group;
            } else {
                index(group.children(), group.id(), items);
            }
        }
        List<List<Entry>> groups = new ArrayList<>();
        groups.add(List.of(Entry.primary("resume", text("Lobby.menu.resume"), () -> { })));
        groups.add(entries(item(items, ClientGUI.FILE_GAME_SAVE, SAVE),
              item(items, ClientGUI.FILE_GAME_QUICK_SAVE, QUICK_SAVE)));

        List<Entry> pages = new ArrayList<>();
        pages.add(Entry.button("players", text("GpuBoard.hud.players.title"), "", true,
              () -> state.dialog = Dialog.PLAYERS));
        pages.add(page(GAME, page(items, GAME_ITEMS)));
        pages.add(page(DISPLAY, page(items, DISPLAY_ITEMS)));
        BoardScene.Command reset = null;
        if (commands != null) {
            String resetLabel = Messages.getString("GameCommands.ResetGame.title");
            String bugLabel = Messages.getString("GameCommands.ReportBug.title");
            List<Entry> gameCommands = new ArrayList<>();
            for (BoardScene.Command command : commands.children()) {
                if (command.label().equals(resetLabel)) {
                    reset = command;
                } else if (!command.label().equals(bugLabel)) {
                    gameCommands.add(entry(command.id(), command.label(), command));
                }
            }
            pages.add(page(COMMANDS, gameCommands.isEmpty() ? List.of() : List.of(gameCommands)));
        }
        if (maps != null && maps.children().size() > 1) {
            List<Entry> boards = new ArrayList<>();
            for (BoardScene.Command board : maps.children()) {
                boards.add(entry(board.id(), board.label(), board));
            }
            pages.add(page(MAPS, List.of(boards)));
        }
        groups.add(present(pages));

        List<List<Entry>> help = new ArrayList<>();
        help.add(entries(Entry.button("keys", text("GpuBoard.hud.menu.keys"),
                    GpuHintLine.key(inputs.preferences(), KeyCommandBind.KEY_BINDS), true,
                    () -> state.dialog = Dialog.HELP),
              item(items, ClientGUI.HELP_CONTENTS, "Lobby.menu.helpContents")));
        help.add(entries(item(items, Messages.getString("CommonMenuBar.helpReportBug"), "Lobby.menu.reportBug"),
              item(items, Messages.getString("CommonMenuBar.helpCopySystemData"), null)));
        help.add(entries(item(items, ClientGUI.HELP_ABOUT, "Lobby.menu.about")));
        groups.add(entries(item(items, ClientGUI.VIEW_CLIENT_SETTINGS, "Lobby.menu.settings"),
              page(HELP, present(help))));

        // The ways out of the game: back to the lobby, where the player may reset it, and, as the window has no
        // close button in fullscreen, always the main menu
        List<Entry> exits = new ArrayList<>();
        if (reset != null) {
            exits.add(Entry.button("lobby", text("GpuBoard.hud.menu.lobby"), reset.detail(), reset.enabled(),
                  reset.action()));
        }
        exits.add(Entry.button("quit", text("GpuBoard.hud.menu.quit"), "", true, source::quitGame));
        groups.add(exits);
        return present(groups);
    }

    /** Files each item of {@code commands} under its action command, the first of a name winning. */
    private static void index(List<BoardScene.Command> commands, String parent, Map<String, BoardScene.Command> items) {
        for (BoardScene.Command command : commands) {
            if (command.children().isEmpty()) {
                items.putIfAbsent(actionCommand(command, parent), command);
            } else {
                index(command.children(), command.id(), items);
            }
        }
    }

    /** A page's groups of the menu bar's items, each group without the items this client's menu bar lacks. */
    private static List<List<Entry>> page(Map<String, BoardScene.Command> items, List<List<String>> groups) {
        List<List<Entry>> page = new ArrayList<>();
        for (List<String> group : groups) {
            List<Entry> entries = new ArrayList<>();
            for (String action : group) {
                entries.add(item(items, action, null));
            }
            page.add(present(entries));
        }
        return present(page);
    }

    /** A page entry, or null for a page with nothing on it. */
    private static Entry page(String key, List<List<Entry>> groups) {
        return groups.isEmpty() ? null : Entry.page(key, text(key), "", groups);
    }

    /**
     * The menu bar's item {@code action} as an entry, under the label of {@code labelKey} or else its own; null when
     * the menu bar lacks it.
     */
    private static Entry item(Map<String, BoardScene.Command> items, String action, String labelKey) {
        BoardScene.Command command = items.get(action);
        if (command == null) {
            return null;
        }
        return entry(action, labelKey == null ? command.label() : text(labelKey), command);
    }

    /** A command as an entry: a group as a page, a check item as a switch, any other as a button. */
    private static Entry entry(String id, String label, BoardScene.Command command) {
        String tip = tip(command);
        if (!command.children().isEmpty()) {
            List<Entry> children = new ArrayList<>();
            for (BoardScene.Command child : command.children()) {
                children.add(entry(child.id(), child.label(), child));
            }
            return command.enabled() ? Entry.page(id, label, tip, List.of(children))
                  : Entry.button(id, label, tip, false, () -> { });
        }
        if (command.selected() != null) {
            return Entry.toggle(id, label, tip, command.enabled(), command.selected(), command.action());
        }
        return Entry.button(id, label, tip, command.enabled(), command.action());
    }

    private static String tip(BoardScene.Command command) {
        String detail = command.detail();
        String shortcut = command.shortcut();
        if (shortcut.isEmpty()) {
            return detail;
        }
        return detail.isEmpty() ? shortcut : detail + " (" + shortcut + ")";
    }

    /** The entries that are there: the menu bar may lack any item, which leaves its entry null. */
    private static List<Entry> entries(Entry... entries) {
        List<Entry> present = new ArrayList<>(entries.length);
        for (Entry entry : entries) {
            if (entry != null) {
                present.add(entry);
            }
        }
        return present;
    }

    /** {@code list} without its nulls and empty groups. */
    private static <T> List<T> present(List<T> list) {
        List<T> present = new ArrayList<>();
        for (T item : list) {
            if (item != null && !(item instanceof List<?> group && group.isEmpty())) {
                present.add(item);
            }
        }
        return present;
    }

    /**
     * A menu item's action command, from its own menu key ("{action command}:{text}", GpuBoardActions.menuKey): its id
     * is the key, after its group's id and a '/' when the group's id begins it. A text may hold '/' or ':' ("Ruler /
     * LOS Tool"), so the key is never split at either. "" for the groups that the source adds.
     */
    static String actionCommand(BoardScene.Command command, String parent) {
        String id = command.id();
        String key = !parent.isEmpty() && id.startsWith(parent + "/") ? id.substring(parent.length() + 1) : id;
        int colon = key.indexOf(':');
        return colon < 0 ? "" : key.substring(0, colon);
    }
}
