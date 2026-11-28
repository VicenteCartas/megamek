/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.onChange;
import static megamek.client.ui.gdx.UiKit.text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.gpu.BoardSource.UiPreferences.Overlays;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.util.KeyCommandBind;

/**
 * The Overlays popover (battle HUD layout, zone 4), which the utility bar's Overlays button opens under itself: while
 * deploying, deployment's expected damage, then thirteen checkboxes in four groups, two to a line, for the overlays that
 * can be visually busy. Each checkbox is one setting, the same in the 3D and the Tactical View (status badges and level
 * shading draw only in the Tactical View, so the 3D view greys them out): a View-menu item, which
 * the row runs and shows as selected (as the conditions card does), or a client setting, which the row toggles on the
 * Swing thread and shows as the published preferences have it. The popover keeps no state of its own.
 */
final class GpuTacticalOverlays implements GpuHud.Component {
    private static final String KEYS = "GpuBoard.hud.overlays.";
    /** The checkbox of a row whose View-menu item is missing or disabled. */
    private static final float UNAVAILABLE = .45f;
    /** A checkbox's height in the grid. */
    private static final float ROW = 22;

    /**
     * A row: its group caption's key, its label's key, and its View-menu item, or its client setting; a Tactical-only
     * setting greys out in the 3D view, still settable for the Tactical View.
     */
    private record Row(String group, String key, String menuItem, Predicate<Overlays> setting,
          Consumer<GUIPreferences> toggle, boolean tacticalOnly) { }

    private static final List<Row> ROWS = List.of(
          tacticalSetting("units", "statusBadges", Overlays::statusBadges,
                GUIPreferences::toggleTacticalStatusBadges),
          view("units", "links", ClientGUI.VIEW_TOGGLE_UNIT_LINKS),
          setting("units", "botOrders", Overlays::botOrders, GUIPreferences::toggleTacticalBotOrders),
          view("combat", "envelope", ClientGUI.VIEW_MOVE_ENV),
          setting("combat", "rearArcs", Overlays::rearArcs, GUIPreferences::toggleTacticalRearArcs),
          view("combat", "losShading", ClientGUI.VIEW_TOGGLE_FOV_DARKEN),
          view("combat", "sensorRanges", ClientGUI.VIEW_TOGGLE_SENSOR_RANGE),
          setting("ecm", "fields", Overlays::ecmFields, GUIPreferences::toggleEcmTacticalFields),
          setting("ecm", "jamming", Overlays::ecmHatch, GUIPreferences::toggleEcmTacticalHatch),
          view("map", "coordinates", ClientGUI.VIEW_TOGGLE_HEX_COORDS),
          view("map", "types", ClientGUI.VIEW_TOGGLE_HEX_TYPES),
          tacticalSetting("map", "levelShading", Overlays::levelShading, GUIPreferences::toggleTacticalLevelShading),
          view("map", "distanceRings", ClientGUI.VIEW_TOGGLE_FOV_HIGHLIGHT));

    private final GpuHudState state;
    private final Table root = new Table();
    private final Label summary;
    private final UiKit.Checkbox damage;
    private final List<UiKit.Checkbox> boxes = new ArrayList<>();
    private final List<Label> tips = new ArrayList<>();
    /** Each View row's item of the current frame, or null when it is missing; in the order of {@link #ROWS}. */
    private final BoardScene.Command[] items = new BoardScene.Command[ROWS.size()];
    /** Each View row's key as its tooltip names it, so the tooltip changes only with the key. */
    private final String[] shortcuts = new String[ROWS.size()];
    /** Whether the Tactical-only rows are laid out for the Tactical View, or null before the first frame. */
    private Boolean tacticalShown;

    GpuTacticalOverlays(GpuHudKit kit, GpuBoardSource source, GpuHudState state,
          Consumer<GpuHudState.DeployOverlay> toggle) {
        this.state = state;
        UiKit ui = kit.ui;
        root.setName("tactical-overlays");
        root.setBackground(ui.skin.getDrawable("panel-pop"));
        root.pad(8, 0, 8, 0);
        root.defaults().growX();
        Table head = new Table();
        head.pad(2, 12, 8, 12);
        head.add(ui.label(UiTheme.upper(text(KEYS + "title")), "hud-main", 13, UiTheme.TEXT)).left();
        head.add().expandX();
        summary = ui.label("", "hud-small", 11, UiTheme.MUTED);
        head.add(summary).right();
        root.add(head).row();
        root.add(new Image(ui.skin.getDrawable("rule"))).height(1).row();

        // Expected damage, a Combat row of the HUD's own: while deploying and moving, how hard the acting unit (or the
        // hovered enemy) hits each hex.
        damage = ui.checkbox(text(KEYS + "damage"), false);
        damage.setName("overlay-damage");
        damage.left();
        ui.tip(damage).getActor().setText(text("GpuBoard.hud.deploy.overlayTip.DAMAGE"));
        onChange(damage, () -> toggle.accept(GpuHudState.DeployOverlay.DAMAGE));

        Table grid = null;
        for (int index = 0; index < ROWS.size(); index++) {
            Row row = ROWS.get(index);
            if (index == 0 || !ROWS.get(index - 1).group().equals(row.group())) {
                if (grid != null && ROWS.get(index - 1).group().equals("combat")) {
                    grid.add(damage).padRight(8);
                    grid.row();
                }
                root.add(caption(ui, text(KEYS + row.group()))).pad(9, 12, 3, 12).row();
                grid = new Table();
                grid.pad(0, 8, 0, 8);
                grid.defaults().left().growX().uniformX().height(ROW);
                root.add(grid).row();
            }
            UiKit.Checkbox box = ui.checkbox(text(KEYS + row.key()), false);
            box.setName("overlay-" + row.key());
            box.left();
            int at = index;
            onChange(box, () -> {
                if (row.menuItem() == null) {
                    source.command(() -> row.toggle().accept(GUIPreferences.getInstance()));
                } else if (items[at] != null && items[at].enabled()) {
                    items[at].action().run();
                }
            });
            tips.add(ui.tip(box).getActor());
            tips.get(index).setText(text(KEYS + row.key() + "Tip"));
            boxes.add(box);
            grid.add(box).padRight(8);
            if (grid.getCells().size % 2 == 0) {
                grid.row();
            }
        }
    }

    /** A group's caption (.pop .gc). */
    private static Label caption(UiKit ui, String text) {
        Label caption = ui.label(UiTheme.upper(text), "hud-caption", 10.5f, UiTheme.MUTED);
        caption.setName("overlays-group");
        return caption;
    }

    private static Row setting(String group, String key, Predicate<Overlays> setting,
          Consumer<GUIPreferences> toggle) {
        return new Row(group, key, null, setting, toggle, false);
    }

    private static Row tacticalSetting(String group, String key, Predicate<Overlays> setting,
          Consumer<GUIPreferences> toggle) {
        return new Row(group, key, null, setting, toggle, true);
    }

    private static Row view(String group, String key, String menuItem) {
        return new Row(group, key, menuItem, null, null, false);
    }

    @Override
    public Actor actor() {
        return root;
    }

    @Override
    public void update(GpuHud.Inputs inputs) {
        Overlays overlays = inputs.preferences().overlays();
        boolean tactical = inputs.view().tactical();
        boolean viewChanged = !Boolean.valueOf(tactical).equals(tacticalShown);
        tacticalShown = tactical;
        int on = 0;
        for (int index = 0; index < ROWS.size(); index++) {
            Row row = ROWS.get(index);
            UiKit.Checkbox box = boxes.get(index);
            boolean value;
            if (row.menuItem() == null) {
                value = row.setting().test(overlays);
                if (row.tacticalOnly() && viewChanged) {
                    box.getColor().a = tactical ? 1 : UNAVAILABLE;
                    tips.get(index).setText(text(KEYS + row.key() + "Tip")
                          + (tactical ? "" : " " + text(KEYS + "tacticalOnly")));
                }
            } else {
                BoardScene.Command item = GpuBoardActions.menuItem(inputs.frame().globalCommands(), row.menuItem());
                items[index] = item;
                boolean available = item != null && item.enabled();
                value = item != null && Boolean.TRUE.equals(item.selected());
                box.setTouchable(available ? Touchable.enabled : Touchable.disabled);
                box.getColor().a = available ? 1 : UNAVAILABLE;
                String shortcut = item == null || item.shortcut() == null ? "" : item.shortcut();
                if (!shortcut.equals(shortcuts[index])) {
                    // A View row's tooltip names the menu and the item's key.
                    shortcuts[index] = shortcut;
                    tips.get(index).setText(text(KEYS + row.key() + "Tip") + " "
                          + (shortcut.isEmpty() ? text(KEYS + "viewMenu") : text(KEYS + "viewMenuKey", shortcut)));
                }
            }
            if (box.isTicked() != value) {
                box.ticked(value);
            }
            on += value ? 1 : 0;
        }
        boolean available = GpuHud.damagePhase(inputs.frame().status());
        damage.setTouchable(available ? Touchable.enabled : Touchable.disabled);
        damage.getColor().a = available ? 1 : UNAVAILABLE;
        boolean shown = available && state.deployOverlay == GpuHudState.DeployOverlay.DAMAGE;
        if (damage.isTicked() != shown) {
            damage.ticked(shown);
        }
        on += shown ? 1 : 0;
        String key = inputs.preferences().binds().stream()
              .filter(bind -> bind.command() == KeyCommandBind.TACTICAL_OVERLAYS && bind.keyCode() != 0)
              .map(GpuBoardSource.Bind::text).filter(text -> !text.isEmpty()).findFirst().orElse("");
        summary.setText(key.isEmpty() ? text(KEYS + "summary", on) : text(KEYS + "summaryKey", on, key));
    }
}
