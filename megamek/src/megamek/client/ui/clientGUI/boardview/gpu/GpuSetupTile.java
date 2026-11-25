/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.onChange;

import java.util.function.Consumer;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;

/**
 * One deployment setup choice as a tile (Core Rulebook p. 224): its caption over its value, and a switch for an on/off
 * choice or a chevron for a list or a count. A choice that differs from how the unit started out is framed in amber
 * with an amber value. The dock's option row and its More panel show the same tile; a click hands the tile to
 * {@code choose}, which flips the switch or opens the list or slider above it.
 */
final class GpuSetupTile {
    /** The tile's height: a caption over its value. */
    static final float HEIGHT = 50;
    private static final String SEPARATOR = " \u00B7 ";

    private final UiKit ui;
    private final UiButton button;
    private final Label caption;
    private final Label value;
    private final UiKit.Icon mark;
    private final TextTooltip tip;
    private final TextButton.TextButtonStyle plain;
    private final TextButton.TextButtonStyle changed;

    GpuSetupTile(UiKit ui, String name, Consumer<Actor> choose) {
        this.ui = ui;
        plain = ui.skin.get("hud", TextButton.TextButtonStyle.class);
        changed = new TextButton.TextButtonStyle(plain);
        changed.up = ui.skin.newDrawable("button-auto", UiTheme.AMBER);
        changed.over = changed.up;
        changed.down = changed.up;
        button = new UiButton(ui, "hud");
        button.setName(name);
        button.pad(5, 8, 5, 7);
        caption = ui.label("", "hud-caption", 9.5f, UiTheme.MUTED);
        caption.setEllipsis(true);
        value = ui.label("", "hud-title", 13, Color.WHITE);
        value.setEllipsis(true);
        mark = ui.icon("unfold-more", 16, UiTheme.MUTED);
        Table bottom = new Table();
        bottom.add(value).growX().minWidth(0).left();
        bottom.add(mark).padLeft(4);
        button.add(caption).growX().minWidth(0).left().row();
        button.add(bottom).growX().minWidth(0).padTop(3);
        tip = ui.tip(button);
        onChange(button, () -> choose.accept(button));
    }

    UiButton button() {
        return button;
    }

    /** Shows {@code tile}: its caption and value, its switch or chevron, the amber frame when changed, and its tip. */
    void show(GpuDeploySetup.Tile tile) {
        caption.setText(UiTheme.upper(tile.caption()));
        value.setText(value(tile));
        value.setColor(tile.changed() ? UiTheme.AMBER : UiTheme.TEXT);
        TextButton.TextButtonStyle style = tile.changed() ? changed : plain;
        if (button.getStyle() != style) {
            button.setStyle(style);
        }
        String icon = tile.kind() != GpuDeploySetup.Kind.TOGGLE ? "unfold-more"
              : tile.on() ? "toggle-on" : "toggle-off";
        mark.setDrawable(ui.skin.getDrawable("icon-" + icon));
        boolean lit = tile.kind() == GpuDeploySetup.Kind.TOGGLE && tile.on() && !tile.isLocked();
        mark.setColor(!lit ? UiTheme.MUTED : tile.changed() ? UiTheme.AMBER : UiTheme.MINT);
        // A locked switch shows its state, dimmed, and its tip says what frees it.
        button.setDisabled(tile.isLocked());
        if (tile.isLocked() && !tile.changed()) {
            value.setColor(UiTheme.MUTED);
        }
        String detail = tile.isLocked() ? tile.locked() : tile.tip();
        // A narrow tile clips long captions and values; the tip names both in full.
        tip.getActor().setText(tile.caption() + SEPARATOR + value(tile) + (detail.isEmpty() ? "" : "\n" + detail));
    }

    /** A tile's value as it reads: the active count of the heat sinks' step, else the state's name. */
    static String value(GpuDeploySetup.Tile tile) {
        return tile.shown(tile.selected());
    }
}
