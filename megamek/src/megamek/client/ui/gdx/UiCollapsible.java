/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import megamek.client.ui.design.DesignTokens;

/**
 * A collapsible section (docs/design-system/tokens.md, Sections): a {@code control.sm} high header row with the
 * chevron, the overline title, the {@code border.subtle} line to the far edge and an optional muted detail at the end,
 * over content that opens and closes. The header takes the hover wash and the focus ring; a click on it, or Enter or
 * Space with the focus on it, opens or closes the section. Closed content leaves the tree, so its controls drop out of
 * the dialog's Tab order. For what a screen only needs now and then, such as a setup's tuning below its essentials.
 */
public final class UiCollapsible extends Table {
    private final Header header;
    private final Cell<Actor> body;
    private final Actor content;
    private final UiKit.Icon chevron;
    private final Label title;
    private final Label detail;
    private final UiKit kit;
    private Runnable onToggle = () -> { };
    private boolean open;

    UiCollapsible(UiKit kit, String text, Actor content, boolean open) {
        this.kit = kit;
        this.content = content;
        DesignTokens tokens = DesignTokens.shipped();
        float sm = tokens.space().get("sm");
        float chevronSize = tokens.size().get("icon.sm");
        top().left();
        header = new Header(kit);
        header.left();
        header.pad(0, sm, 0, sm);
        chevron = kit.icon("chevron-right", chevronSize, UiTheme.tokenColor("text.secondary"));
        header.add(chevron).size(chevronSize).padRight(sm);
        title = kit.roleTypeLabel("overline", text, "text.secondary");
        header.add(title).left();
        header.add(kit.solid("border.subtle")).height(1).growX().padLeft(sm);
        detail = kit.typeLabel("", "small", "text.muted");
        header.add(detail).right().padLeft(sm);
        UiKit.onChange(header, () -> {
            if (!header.isDisabled()) {
                open(!this.open);
                onToggle.run();
            }
        });
        add(header).growX().height(tokens.size().get("control.sm")).row();
        body = add((Actor) null).growX().minWidth(0);
        open(open);
    }

    /** Whether the content shows. */
    public boolean isOpen() {
        return open;
    }

    /** Opens or closes the section, without running the toggle action. */
    public void open(boolean value) {
        open = value;
        chevron.setDrawable(kit.skin.getDrawable("icon-chevron-" + (value ? "down" : "right")));
        body.setActor(value ? content : null);
        body.padTop(value ? DesignTokens.shipped().space().get("sm") : 0);
        invalidateHierarchy();
    }

    /** Runs {@code action} after the user opens or closes the section. */
    public UiCollapsible onToggle(Runnable action) {
        onToggle = action;
        return this;
    }

    /** Shows {@code text} muted at the header's end, such as how many targets the closed section holds. */
    public void detail(String text) {
        detail.setText(text);
    }

    /** The header, which takes the focus and the clicks. */
    public Button header() {
        return header;
    }

    /** A disabled section keeps its state and shows its title in text.disabled. */
    public void setDisabled(boolean disabled) {
        header.setDisabled(disabled);
    }

    /** The header row: the hover wash, the title in text.primary under the pointer, the focus ring inside the row. */
    private final class Header extends Button {
        private final Drawable ring;

        private Header(UiKit kit) {
            super(headerStyle(kit));
            ring = kit.skin.getDrawable("token-row-focus");
        }

        @Override
        public boolean isChecked() {
            return open;
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            String role = isDisabled() ? "text.disabled" : isOver() || hasKeyboardFocus() ? "text.primary"
                  : "text.secondary";
            title.getStyle().fontColor = UiTheme.tokenColor(role);
            chevron.setColor(UiTheme.tokenColor(role));
            super.draw(batch, parentAlpha);
            if (hasKeyboardFocus()) {
                Color previous = batch.getColor().cpy();
                batch.setColor(1, 1, 1, getColor().a * parentAlpha);
                ring.draw(batch, getX(), getY(), getWidth(), getHeight());
                batch.setColor(previous);
            }
        }
    }

    private static Button.ButtonStyle headerStyle(UiKit kit) {
        Button.ButtonStyle style = new Button.ButtonStyle();
        style.over = kit.skin.getDrawable("list-row-over");
        return style;
    }
}
