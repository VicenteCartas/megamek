/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import jakarta.annotation.Nullable;
import megamek.client.ui.gdx.UiKit;
import megamek.client.ui.gdx.UiTheme;

/**
 * A unit's changed starting setup on an amber tag. Collapsed, it names the changes in short ("MASC OFF · +1"); while
 * the pointer is over it, it lists each change with its state, then what the setup costs. It opens upward from its
 * bottom edge, so nothing under the pointer moves. With an anchor (the dock's main button) it shows only opened, above
 * the anchor's right end, while the pointer is over the anchor.
 */
final class GpuSetupTag extends Table {
    /** The gap between the anchor and the card above it. */
    private static final float LIFT = 6;

    private final UiKit ui;
    private final String name;
    private final @Nullable Button anchor;
    private final ClickListener hover = new ClickListener();
    private GpuDeploySetup.Choice setup;
    private boolean open;
    private float collapsedHeight;

    GpuSetupTag(UiKit ui, String name, @Nullable Button anchor) {
        this.ui = ui;
        this.name = name;
        this.anchor = anchor;
        setName(name + "-box");
        setBackground(ui.skin.getDrawable("deploy-tag"));
        setTouchable(anchor == null ? Touchable.enabled : Touchable.disabled);
        addListener(hover);
        setVisible(false);
    }

    /** Shows {@code setup}'s changes, rebuilt only when they differ; null clears it (an anchored card then hides). */
    void show(@Nullable GpuDeploySetup.Choice setup) {
        if (setup == null || setup.changes() == 0) {
            this.setup = null;
            if (anchor != null) {
                setVisible(false);
            }
        } else if (!setup.equals(this.setup)) {
            this.setup = setup;
            open = opens();
            rebuild();
        }
    }

    /** Its height collapsed, which the controls around it are laid out by, whether or not it is open. */
    float collapsedHeight() {
        return collapsedHeight;
    }

    boolean isOpen() {
        return open;
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        if (anchor != null) {
            setVisible(setup != null && anchor.isOver() && anchor.ascendantsVisible());
            if (isVisible() && getParent() != null) {
                Vector2 corner = anchor.localToActorCoordinates(getParent(),
                      new Vector2(anchor.getWidth(), anchor.getHeight()));
                setPosition(Math.round(corner.x - getWidth()), Math.round(corner.y + LIFT));
            }
        } else if (setup != null && opens() != open) {
            open = opens();
            float middle = getX() + getWidth() / 2;
            float bottom = getY();
            rebuild();
            setPosition(Math.round(middle - getWidth() / 2), bottom);
        }
    }

    private boolean opens() {
        return anchor != null || hover.isOver();
    }

    private void rebuild() {
        clearChildren();
        add(dot()).size(6).padRight(5);
        Label brief = ui.label(UiTheme.upper(setup.tag()), "hud-button", 10, UiTheme.AMBER);
        brief.setName(name);
        add(brief);
        collapsedHeight = getPrefHeight();
        if (open) {
            clearChildren();
            for (GpuDeploySetup.Tile tile : setup.tiles()) {
                if (tile.changed()) {
                    add(dot()).size(6).padRight(5);
                    Label caption = ui.label(UiTheme.upper(tile.caption()), "hud-button", 10, UiTheme.TEXT);
                    add(caption).left();
                    Label state = ui.label(UiTheme.upper(tile.shown(tile.selected())), "hud-button", 10,
                          UiTheme.AMBER);
                    state.setName(name + "-" + tile.id());
                    add(state).left().padLeft(10).row();
                }
            }
            for (String line : setup.consequences()) {
                add(ui.label("\u25B2 " + line, "hud-small", 11, UiTheme.AMBER)).colspan(3).left().padTop(4).row();
            }
        }
        pack();
    }

    private Image dot() {
        return new Image(ui.skin.getDrawable("dot"));
    }
}
