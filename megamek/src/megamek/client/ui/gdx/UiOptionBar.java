/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.Pools;
import megamek.client.ui.design.DesignTokens;

/**
 * A row of mutually exclusive options, the base of {@link UiTabs} and {@link UiSegmented}: exactly one is selected. A
 * click on an option, or Left and Right while the bar has the keyboard focus, selects it and fires a ChangeEvent from
 * the bar; a view's own change ({@link #select}) fires none. The bar draws the options' faces itself.
 */
public abstract class UiOptionBar extends Table {
    final UiTheme.OptionStyle colors;
    final float border = DesignTokens.shipped().size().get("border");
    private final List<Option> options = new ArrayList<>();
    private final float height;
    private final float side;
    private int selected;
    private boolean disabled;

    UiOptionBar(UiKit kit, boolean compact, String... labels) {
        if (labels.length == 0) {
            throw new IllegalArgumentException("An option bar needs options");
        }
        colors = kit.skin.get(UiTheme.OptionStyle.class);
        DesignTokens tokens = DesignTokens.shipped();
        height = tokens.size().get(compact ? "control.sm" : "control.md");
        side = tokens.space().get(compact ? "md" : "lg");
        for (int i = 0; i < labels.length; i++) {
            options.add(new Option(kit, i, labels[i]));
        }
        addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                if (disabled) {
                    return false;
                }
                return switch (keycode) {
                    case Input.Keys.LEFT -> {
                        choose(enabled(selected, -1));
                        yield true;
                    }
                    case Input.Keys.RIGHT -> {
                        choose(enabled(selected, 1));
                        yield true;
                    }
                    default -> false;
                };
            }
        });
    }

    /** The bar's height in stage units: control.sm when compact, control.md otherwise. */
    public float barHeight() {
        return height;
    }

    public int count() {
        return options.size();
    }

    /** The option at {@code index}; a gallery freezes its look through it. */
    public Option option(int index) {
        return options.get(index);
    }

    List<Option> options() {
        return options;
    }

    public int getSelectedIndex() {
        return selected;
    }

    /** Selects the option at {@code index} without an event. */
    public void select(int index) {
        if (index < 0 || index >= options.size()) {
            throw new IndexOutOfBoundsException(index);
        }
        selected = index;
    }

    public boolean isDisabled() {
        return disabled;
    }

    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
        for (Option option : options) {
            option.setDisabled(disabled);
        }
    }

    /** A user's selection: selects {@code index} and fires a ChangeEvent, unless it is selected or out of range. */
    void choose(int index) {
        if (disabled || index < 0 || index >= options.size() || index == selected
              || options.get(index).isDisabled()) {
            return;
        }
        selected = index;
        ChangeListener.ChangeEvent event = Pools.obtain(ChangeListener.ChangeEvent.class);
        try {
            fire(event);
        } finally {
            Pools.free(event);
        }
    }

    /** The first enabled option from {@code index} in steps of {@code step}, or -1 past the bar's end. */
    private int enabled(int index, int step) {
        int next = index + step;
        while (next >= 0 && next < options.size() && options.get(next).isDisabled()) {
            next += step;
        }
        return next;
    }

    /** Whether the option at {@code index} shows the focus ring: frozen by a view, or selected in a focused bar. */
    boolean ringed(int index) {
        return !disabled && (options.get(index).focused || (index == selected && hasKeyboardFocus()));
    }

    /** The selected option's label color, disabled or not; null: the active or disabled one, as the others. */
    Color selectedText() {
        return null;
    }

    /** Draws {@code line} tinted {@code color}, the bar's alpha applied, and restores the batch color. */
    void line(Batch batch, Color color, float parentAlpha, float x, float y, float width, float height) {
        Color tint = getColor();
        batch.setColor(color.r, color.g, color.b, color.a * tint.a * parentAlpha);
        colors.line.draw(batch, x, y, width, height);
        batch.setColor(tint.r, tint.g, tint.b, tint.a * parentAlpha);
    }

    /** One option: a label with side padding, in the toggle button's text colors. */
    public final class Option extends Button {
        private final int index;
        private final Label label;
        private boolean hovered;
        private boolean held;
        private boolean focused;

        Option(UiKit kit, int index, String text) {
            super(new ButtonStyle());
            this.index = index;
            // White style color: the label's own color, set per state, is then the token color exactly
            label = kit.roleLabel("tab", text, 13, Color.WHITE);
            add(label).padLeft(side).padRight(side);
            // The bar fires the one event its listeners hear; the button's own stops here
            addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    event.stop();
                    choose(Option.this.index);
                }
            });
        }

        public Label label() {
            return label;
        }

        public boolean isSelected() {
            return index == selected;
        }

        /** Shows the hover look without a pointer, as a gallery does. */
        public Option hovered(boolean value) {
            hovered = value;
            return this;
        }

        @Override
        public boolean isOver() {
            return hovered || super.isOver();
        }

        /** Shows the pressed look without a pointer, as a gallery does. */
        public Option held(boolean value) {
            held = value;
            return this;
        }

        @Override
        public boolean isPressed() {
            return held || super.isPressed();
        }

        /** Shows the focus ring without keyboard focus, as a gallery does. */
        public Option focused(boolean value) {
            focused = value;
            return this;
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            boolean active = isSelected() || isOver() || isPressed();
            Color on = isSelected() ? selectedText() : null;
            label.setColor(on != null ? on : isDisabled() ? colors.disabledText : active ? colors.activeText
                  : colors.text);
            super.draw(batch, parentAlpha);
        }
    }
}
