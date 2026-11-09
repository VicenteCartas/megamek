/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Pools;
import megamek.client.ui.design.DesignTokens;

/**
 * A token select (docs/design-system/tokens.md, Select): a face showing the current item or a muted placeholder and a
 * chevron, that opens a {@link UiListPopup} of its items. Regular and compact faces are the input box; an inline select
 * has no box, secondary text that turns primary under the pointer, open or focused, and a dotted underline. A click,
 * or Enter, Space or Down while it has the keyboard focus, opens the list; choosing another item there fires a
 * ChangeEvent from the select. A view's own change ({@link #select}) fires none.
 */
public final class UiSelect extends Table {
    /** The face: an input box at control.md or control.sm, or inline text. */
    public enum Kind { REGULAR, COMPACT, INLINE }

    private final UiTheme.SelectStyle style;
    private final Kind kind;
    private final String[] items;
    private final Label label;
    private final UiKit.Icon chevron;
    private final UiListPopup popup;
    private final ClickListener clicks;
    private final Cell<Label> labelCell;
    private final BitmapFont font;
    private final float border;
    private final float height;
    private float widest;
    private int selected;
    private String placeholder;
    private boolean disabled;
    private boolean hovered;
    private boolean focused;
    private boolean open;

    UiSelect(UiKit kit, Kind kind, String... items) {
        this.kind = kind;
        this.items = items.clone();
        style = kit.skin.get(UiTheme.SelectStyle.class);
        DesignTokens tokens = DesignTokens.shipped();
        border = tokens.size().get("border");
        height = tokens.size().get(kind == Kind.REGULAR ? "control.md" : "control.sm");
        float icon = tokens.size().get("icon.sm");
        font = kit.skin.getFont("hud-body");
        // White style color: the label's own color, set per state, is then the token color exactly
        label = new Label("", new Label.LabelStyle(font, Color.WHITE));
        label.setEllipsis(true);
        chevron = kit.icon("chevron-down", icon, Color.WHITE);
        GlyphLayout layout = new GlyphLayout();
        for (String item : items) {
            layout.setText(font, item);
            widest = Math.max(widest, layout.width);
        }
        float side = kind == Kind.INLINE ? tokens.space().get("xs") : border + tokens.space().get("md");
        float trailing = kind == Kind.INLINE ? tokens.space().get("xs") : border + tokens.space().get("sm");
        // The widest item is the preferred width, so the face doesn't jump; held narrower, the value ellipsizes
        labelCell = add(label).left().growX().minWidth(0).prefWidth(widest);
        add(chevron).size(icon).padLeft(tokens.space().get("xs"));
        padLeft(side).padRight(trailing);
        popup = new UiListPopup(kit, items).onChoose(this::choose);
        selected = items.length == 0 ? -1 : 0;
        refresh();
        setTouchable(Touchable.enabled);
        clicks = new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                if (popup.isOpen()) {
                    popup.close();
                } else {
                    open(getStage() == null ? null : getStage().getKeyboardFocus());
                }
            }
        };
        addListener(clicks);
        addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                if (disabled || event.getTarget() != UiSelect.this) {
                    return false;
                }
                return switch (keycode) {
                    case Input.Keys.ENTER, Input.Keys.NUMPAD_ENTER, Input.Keys.SPACE, Input.Keys.DOWN -> {
                        open(UiSelect.this);
                        yield true;
                    }
                    default -> false;
                };
            }
        });
    }

    public Kind kind() {
        return kind;
    }

    public int count() {
        return items.length;
    }

    /** The index of the current item, or -1 while the placeholder shows. */
    public int getSelectedIndex() {
        return selected;
    }

    /** The current item, or null while the placeholder shows. */
    public String getSelected() {
        return selected < 0 ? null : items[selected];
    }

    /** Makes the item at {@code index} current without an event; -1 shows the placeholder. */
    public UiSelect select(int index) {
        if (index < -1 || index >= items.length) {
            throw new IndexOutOfBoundsException(index);
        }
        selected = index;
        refresh();
        return this;
    }

    /**
     * Names the item at {@code index} {@code text} in place, without an event: the current item, an open list and the
     * keyboard focus stay as they are.
     */
    public UiSelect relabel(int index, String text) {
        if (index < 0 || index >= items.length) {
            throw new IndexOutOfBoundsException(index);
        }
        items[index] = text;
        popup.label(index).setText(text);
        popup.invalidateHierarchy();
        GlyphLayout layout = new GlyphLayout();
        widest = 0;
        for (String item : items) {
            layout.setText(font, item);
            widest = Math.max(widest, layout.width);
        }
        if (placeholder != null) {
            layout.setText(font, placeholder);
            labelCell.prefWidth(Math.max(widest, layout.width));
        } else {
            labelCell.prefWidth(widest);
        }
        refresh();
        invalidateHierarchy();
        return this;
    }

    /** Shows {@code text} in the muted placeholder color until the user chooses an item; nothing is current. */
    public UiSelect placeholder(String text) {
        placeholder = text;
        GlyphLayout layout = new GlyphLayout(font, text);
        labelCell.prefWidth(Math.max(widest, layout.width));
        selected = -1;
        refresh();
        invalidateHierarchy();
        return this;
    }

    /** The list it opens; a test or a view reads it. */
    public UiListPopup popup() {
        return popup;
    }

    public boolean isOpen() {
        return popup.isOpen();
    }

    /** Opens the list, unless it is disabled or empty; the keyboard focus comes back to the select on close. */
    public void open() {
        open(this);
    }

    private void open(Actor focusAfter) {
        if (!disabled && items.length > 0 && getStage() != null) {
            popup.show(this, selected, focusAfter);
        }
    }

    public boolean isDisabled() {
        return disabled;
    }

    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
        if (disabled) {
            popup.close();
        }
    }

    /** Shows the hover look without a pointer, as a gallery does. */
    public UiSelect hovered(boolean value) {
        hovered = value;
        return this;
    }

    /** Shows the focus ring without keyboard focus, as a gallery does. */
    public UiSelect focused(boolean value) {
        focused = value;
        return this;
    }

    /** Shows the open look without its list, as a gallery does. */
    public UiSelect opened(boolean value) {
        open = value;
        return this;
    }

    @Override
    public float getPrefHeight() {
        return height;
    }

    @Override
    protected void setStage(Stage stage) {
        if (stage == null) {
            popup.close();
        }
        super.setStage(stage);
    }

    /** A user's choice: makes {@code index} current and fires a ChangeEvent, unless it is current already. */
    private void choose(int index) {
        if (disabled || index < 0 || index >= items.length || index == selected) {
            return;
        }
        selected = index;
        refresh();
        ChangeListener.ChangeEvent event = Pools.obtain(ChangeListener.ChangeEvent.class);
        try {
            fire(event);
        } finally {
            Pools.free(event);
        }
    }

    private void refresh() {
        label.setText(selected < 0 ? (placeholder == null ? "" : placeholder) : items[selected]);
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        boolean over = !disabled && (hovered || clicks.isOver());
        boolean shown = !disabled && (open || popup.isOpen());
        boolean ring = !disabled && (focused || hasKeyboardFocus());
        if (disabled) {
            label.setColor(style.disabledText);
        } else if (selected < 0) {
            label.setColor(style.placeholder);
        } else if (kind == Kind.INLINE && !(over || shown || ring)) {
            label.setColor(style.inlineText);
        } else {
            label.setColor(style.text);
        }
        chevron.setColor(disabled ? style.disabledText : style.chevron);
        Color tint = getColor();
        if (kind != Kind.INLINE) {
            batch.setColor(tint.r, tint.g, tint.b, tint.a * parentAlpha);
            (disabled ? style.faceDisabled : ring ? style.faceFocus : over || shown ? style.faceOver : style.face)
                  .draw(batch, getX(), getY(), getWidth(), getHeight());
        } else if (ring) {
            batch.setColor(tint.r, tint.g, tint.b, tint.a * parentAlpha);
            style.ring.draw(batch, getX(), getY(), getWidth(), getHeight());
        } else {
            Color line = disabled ? style.underlineDisabled : over || shown ? style.underlineOver : style.underline;
            batch.setColor(line.r * tint.r, line.g * tint.g, line.b * tint.b, line.a * tint.a * parentAlpha);
            style.line.draw(batch, getX(), getY(), getWidth(), border);
        }
        batch.setColor(tint.r, tint.g, tint.b, tint.a * parentAlpha);
        super.draw(batch, parentAlpha);
    }
}
