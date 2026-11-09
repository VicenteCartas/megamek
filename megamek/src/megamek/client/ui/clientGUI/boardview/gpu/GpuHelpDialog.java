/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.text;
import static megamek.client.ui.util.KeyCommandBind.BOT_COMMANDS;
import static megamek.client.ui.util.KeyCommandBind.CAMERA_FIT_BOARD;
import static megamek.client.ui.util.KeyCommandBind.CAMERA_RESET;
import static megamek.client.ui.util.KeyCommandBind.CAMERA_ROTATE_LEFT;
import static megamek.client.ui.util.KeyCommandBind.CAMERA_ROTATE_RIGHT;
import static megamek.client.ui.util.KeyCommandBind.CAMERA_TILT_DOWN;
import static megamek.client.ui.util.KeyCommandBind.CAMERA_TILT_UP;
import static megamek.client.ui.util.KeyCommandBind.CANCEL;
import static megamek.client.ui.util.KeyCommandBind.CENTER_ON_SELECTED;
import static megamek.client.ui.util.KeyCommandBind.CLEAR_ORDERS;
import static megamek.client.ui.util.KeyCommandBind.DONE;
import static megamek.client.ui.util.KeyCommandBind.FORCES_GRID;
import static megamek.client.ui.util.KeyCommandBind.GAME_MENU;
import static megamek.client.ui.util.KeyCommandBind.KEY_BINDS;
import static megamek.client.ui.util.KeyCommandBind.LOS_SETTING;
import static megamek.client.ui.util.KeyCommandBind.MINIMAP;
import static megamek.client.ui.util.KeyCommandBind.MOVE_MODE_JUMP;
import static megamek.client.ui.util.KeyCommandBind.MOVE_MODE_RUN;
import static megamek.client.ui.util.KeyCommandBind.MOVE_MODE_WALK;
import static megamek.client.ui.util.KeyCommandBind.NEXT_TARGET;
import static megamek.client.ui.util.KeyCommandBind.NEXT_UNIT;
import static megamek.client.ui.util.KeyCommandBind.NEXT_WEAPON;
import static megamek.client.ui.util.KeyCommandBind.PLAYBACK_NEXT;
import static megamek.client.ui.util.KeyCommandBind.PLAYBACK_PREV;
import static megamek.client.ui.util.KeyCommandBind.PLAYBACK_TOGGLE;
import static megamek.client.ui.util.KeyCommandBind.PREV_TARGET;
import static megamek.client.ui.util.KeyCommandBind.PREV_UNIT;
import static megamek.client.ui.util.KeyCommandBind.PREV_WEAPON;
import static megamek.client.ui.util.KeyCommandBind.ROUND_REPORT;
import static megamek.client.ui.util.KeyCommandBind.SCROLL_EAST;
import static megamek.client.ui.util.KeyCommandBind.SCROLL_NORTH;
import static megamek.client.ui.util.KeyCommandBind.SCROLL_SOUTH;
import static megamek.client.ui.util.KeyCommandBind.SCROLL_WEST;
import static megamek.client.ui.util.KeyCommandBind.SHOW_NAMEPLATES;
import static megamek.client.ui.util.KeyCommandBind.TACTICAL_OVERLAYS;
import static megamek.client.ui.util.KeyCommandBind.TOGGLE_CHAT;
import static megamek.client.ui.util.KeyCommandBind.TOGGLE_ISO;
import static megamek.client.ui.util.KeyCommandBind.TURN_LEFT;
import static megamek.client.ui.util.KeyCommandBind.TURN_RIGHT;
import static megamek.client.ui.util.KeyCommandBind.TWIST_LEFT;
import static megamek.client.ui.util.KeyCommandBind.TWIST_RIGHT;
import static megamek.client.ui.util.KeyCommandBind.UNDO_LAST_STEP;
import static megamek.client.ui.util.KeyCommandBind.UNIT_DISPLAY;
import static megamek.client.ui.util.KeyCommandBind.UNIT_OVERVIEW;
import static megamek.client.ui.util.KeyCommandBind.ZOOM_IN;
import static megamek.client.ui.util.KeyCommandBind.ZOOM_OUT;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.utils.Align;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.util.KeyCommandBind;

/**
 * Compact controls reference: category navigation beside action rows and the current configured shortcuts.
 */
final class GpuHelpDialog implements GpuHud.Component {
    /** The binds of each group, in the order listed: the camera binds and the HUD's hotkeys (C.4). */
    private static final List<Group> GROUPS = List.of(
          new Group("GpuBoard.hud.help.camera", List.of(SCROLL_NORTH, SCROLL_WEST, SCROLL_SOUTH, SCROLL_EAST,
                CAMERA_ROTATE_LEFT, CAMERA_ROTATE_RIGHT, CAMERA_TILT_UP, CAMERA_TILT_DOWN, ZOOM_IN, ZOOM_OUT,
                CAMERA_RESET, CAMERA_FIT_BOARD, CENTER_ON_SELECTED, TOGGLE_ISO)),
          new Group("GpuBoard.hud.help.selection", List.of(NEXT_UNIT, PREV_UNIT, DONE, CANCEL, SHOW_NAMEPLATES)),
          new Group("GpuBoard.hud.help.movement", List.of(MOVE_MODE_WALK, MOVE_MODE_RUN, MOVE_MODE_JUMP, TURN_LEFT,
                TURN_RIGHT, UNDO_LAST_STEP, CLEAR_ORDERS)),
          new Group("GpuBoard.hud.help.weapons", List.of(NEXT_WEAPON, PREV_WEAPON, NEXT_TARGET, PREV_TARGET,
                TWIST_LEFT, TWIST_RIGHT)),
          new Group("GpuBoard.hud.help.playback", List.of(PLAYBACK_TOGGLE, PLAYBACK_PREV, PLAYBACK_NEXT)),
          new Group("GpuBoard.hud.help.panels", List.of(ROUND_REPORT, UNIT_OVERVIEW, UNIT_DISPLAY, FORCES_GRID,
                MINIMAP, TACTICAL_OVERLAYS, LOS_SETTING, KEY_BINDS, GAME_MENU, BOT_COMMANDS, TOGGLE_CHAT)),
          new Group("GpuBoard.hud.help.mouse", List.of()));
    /** The mouse gestures, which no bind names: the message keys of each gesture and of what it does. */
    private static final List<Pair> MOUSE = List.of(
          new Pair("GpuBoard.hud.mouse.leftClick", "GpuBoard.hud.help.leftClick"),
          new Pair("GpuBoard.hud.mouse.ctrlClick", "GpuBoard.hud.help.ctrlClick"),
          new Pair("GpuBoard.hud.mouse.shiftLeftClick", "GpuBoard.hud.help.shiftLeftClick"),
          new Pair("GpuBoard.hud.mouse.altClick", "GpuBoard.hud.help.altClick"),
          new Pair("GpuBoard.hud.mouse.rightDrag", "GpuBoard.hud.hint.pan"),
          new Pair("GpuBoard.hud.mouse.orbitDrag", "GpuBoard.hud.help.orbit"),
          new Pair("GpuBoard.hud.mouse.shortRightClick", "GpuBoard.hud.help.shortRightClick"),
          new Pair("GpuBoard.hud.mouse.wheel", "GpuBoard.hud.help.wheel"));
    /** Fits the camera shortcuts at normal density; smaller windows can scroll the content. */
    private static final float BODY_HEIGHT = 364;

    /** A caption and the binds listed under it. */
    private record Group(String caption, List<KeyCommandBind> binds) { }

    /** A key or gesture and what it does. */
    private record Pair(String key, String description) { }

    private final UiKit ui;
    private final GpuHudState state;
    private final Table root;
    private final Table body = new Table();
    private final ScrollPane scroll;
    private final List<UiButton> categories = new ArrayList<>();
    private int selected;
    private GpuBoardSource.UiPreferences shown;

    GpuHelpDialog(GpuHudKit kit, GpuBoardSource source, GpuHudState state) {
        ui = kit.ui;
        this.state = state;
        root = ui.dialog(text("GpuBoard.hud.help.title"), () -> state.dialog = GpuHudState.Dialog.NONE);
        root.setName("help-dialog");
        root.setBackground(ui.skin.getDrawable("panel-pop"));
        body.top().left();
        scroll = ui.scrollList(body);
        scroll.setFadeScrollBars(false);
        scroll.setScrollbarsOnTop(false);
        Table navigation = new Table().top();
        for (int index = 0; index < GROUPS.size(); index++) {
            int category = index;
            UiButton button = ui.button("hud-plain", null, text(GROUPS.get(index).caption()), null);
            button.left();
            UiKit.onChange(button, () -> {
                selected = category;
                rebuild();
            });
            categories.add(button);
            navigation.add(button).growX().padBottom(3).row();
        }
        Table content = new Table();
        content.add(ui.scrollList(navigation)).width(104).growY().minHeight(0);
        content.add(new Image(ui.skin.getDrawable("rule"))).width(1).growY().padLeft(12).padRight(16);
        content.add(scroll).grow().minWidth(0).minHeight(0);
        root.add(content).grow().minHeight(0).prefHeight(BODY_HEIGHT).padTop(4);
    }

    @Override
    public Actor actor() {
        return root;
    }

    /** Lists the binds when the dialog shows, again whenever the preferences, and with them the keys, change. */
    @Override
    public void update(GpuHud.Inputs inputs) {
        if (state.dialog == GpuHudState.Dialog.HELP && inputs.preferences() != shown) {
            shown = inputs.preferences();
            rebuild();
        }
    }

    private void rebuild() {
        body.clearChildren();
        for (int index = 0; index < categories.size(); index++) {
            categories.get(index).pressed(index == selected);
        }
        if (shown == null) {
            return;
        }
        Group group = GROUPS.get(selected);
        if (group.binds().isEmpty()) {
            MOUSE.forEach(gesture -> pair(new Pair(text(gesture.key()), text(gesture.description()))));
        } else {
            for (KeyCommandBind command : group.binds()) {
                shown.binds().stream().filter(bind -> bind.command() == command && bind.keyCode() != 0)
                      .findFirst().ifPresent(bind -> pair(new Pair(bind.text(),
                            text("KeyBinds.cmdNames." + command.cmd))));
            }
        }
        scroll.setScrollY(0);
        scroll.updateVisualScroll();
    }

    /** A readable action and a right-aligned key badge; long gestures wrap within their column. */
    private void pair(Pair pair) {
        Label description = ui.label(pair.description(), "hud-body", 12, UiTheme.TEXT);
        description.setWrap(true);
        Label key = ui.label(pair.key(), "hud-name", 11.5f, UiTheme.ACCENT);
        key.getStyle().background = ui.skin.getDrawable("chip");
        key.setAlignment(Align.center);
        key.setWrap(true);
        body.add(description).growX().minWidth(0).minHeight(26).padRight(12);
        body.add(key).width(132).padTop(3).padBottom(3).row();
    }
}
