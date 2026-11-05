/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Widget;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;

/**
 * A design-system choice (docs/design-system/tokens.md, Choices): a check box, radio button or switch with its label
 * on the right. A click on either, or Space or Enter while it has the keyboard focus, flips it and fires a ChangeEvent;
 * a view's own change ({@link #setChecked}) fires none. Checked is the primary button's cream fill with a mark in the
 * on-inverse color, faint but still cream while disabled, and an on switch has its thumb on the right, so the state
 * never rests on color alone. Radio buttons are exclusive in
 * a {@link com.badlogic.gdx.scenes.scene2d.ui.ButtonGroup} ({@link UiKit#radioGroup}).
 */
public class UiChoice extends Button {
    /** The kinds of choice. A check box is for a choice confirmed with a form; a switch applies at once. */
    public enum Kind {
        CHECK("box"), RADIO("radio"), SWITCH("track");

        private final String shape;

        Kind(String shape) {
            this.shape = shape;
        }
    }

    private final Kind kind;
    private final UiTheme.ChoiceStyle colors;
    private final Drawable[] faces = new Drawable[UiTheme.CHOICE_STATES.size()];
    private final Drawable focusRing;
    private final Drawable mark;
    private final Drawable dash;
    private final Label label;
    private boolean mixed, hovered, held, focused;

    UiChoice(UiKit kit, Kind kind, String text, boolean checked) {
        super(new ButtonStyle());
        this.kind = kind;
        colors = kit.skin.get(UiTheme.ChoiceStyle.class);
        for (int i = 0; i < faces.length; i++) {
            faces[i] = kit.skin.getDrawable("choice-" + kind.shape + UiTheme.CHOICE_STATES.get(i));
        }
        focusRing = kit.skin.getDrawable("choice-" + kind.shape + "-focus");
        mark = kit.skin.getDrawable(switch (kind) {
            case CHECK -> "icon-check";
            case RADIO -> "choice-dot";
            case SWITCH -> "choice-thumb";
        });
        dash = kit.skin.getDrawable("icon-remove");
        // White style color: draw() sets the label's own color, which Label multiplies by the style's
        label = kit.label(text, "hud-body", 13, Color.WHITE);
        float ring = UiTheme.CHOICE_RING;
        float width = kind == Kind.SWITCH ? Math.round(UiTheme.CHOICE_SIZE * UiTheme.SWITCH_LENGTH)
              : UiTheme.CHOICE_SIZE;
        // a choice with no words of its own needs no gap before them, so it lines up with the controls beside it
        add(new Indicator()).size(width, UiTheme.CHOICE_SIZE).pad(ring).padRight(text == null || text.isEmpty() ? ring : ring + 4);
        add(label);
        setProgrammaticChangeEvents(false);
        super.setChecked(checked);
        // A click or key flips a mixed box to checked; drop the dash before anyone else hears the change
        addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                mixed = false;
            }
        });
        addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                if (isDisabled() || (keycode != Input.Keys.SPACE && keycode != Input.Keys.ENTER)) {
                    return false;
                }
                // A radio button in a group stays selected, as a click leaves it
                if (kind == Kind.RADIO && isChecked() && getButtonGroup() != null) {
                    return true;
                }
                setProgrammaticChangeEvents(true);
                setChecked(!isChecked());
                setProgrammaticChangeEvents(false);
                return true;
            }
        });
    }

    public Kind kind() {
        return kind;
    }

    /** The label beside the indicator. */
    public Label label() {
        return label;
    }

    /** Checks or clears the choice without an event; either drops the mixed look. */
    @Override
    public void setChecked(boolean checked) {
        mixed = false;
        super.setChecked(checked);
    }

    /**
     * Shows a check box mixed (a dash: some of the items it stands for are checked) or clears that, without an event.
     * A mixed box reads as unchecked, so a click checks it.
     */
    public UiChoice mixed(boolean value) {
        if (value) {
            setChecked(false);
        }
        mixed = value && kind == Kind.CHECK;
        return this;
    }

    public boolean isMixed() {
        return mixed;
    }

    /** Shows the hover look without a pointer, as a gallery does. */
    public UiChoice hovered(boolean value) {
        hovered = value;
        return this;
    }

    @Override
    public boolean isOver() {
        return hovered || super.isOver();
    }

    /** Shows the pressed look without a pointer, as a gallery does. */
    public UiChoice held(boolean value) {
        held = value;
        return this;
    }

    @Override
    public boolean isPressed() {
        return held || super.isPressed();
    }

    /** Shows the focus ring while a view moves its own keyboard selection here; keyboard focus shows it anyway. */
    public UiChoice focused(boolean value) {
        focused = value;
        return this;
    }

    /** The face for the current state: an index into {@link UiTheme#CHOICE_STATES}. */
    int state() {
        int on = isChecked() || mixed ? 4 : 0;
        if (isDisabled()) {
            return on + 3;
        }
        if (isPressed()) {
            return on + 2;
        }
        return on + (isOver() ? 1 : 0);
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        label.setColor(isDisabled() ? colors.disabledText : colors.text);
        super.draw(batch, parentAlpha);
    }

    /** The box, circle or track, its mark and the focus ring. */
    private final class Indicator extends Widget {
        @Override
        public void draw(Batch batch, float parentAlpha) {
            Color tint = getColor();
            float alpha = tint.a * parentAlpha;
            float x = getX();
            float y = getY();
            float width = getWidth();
            float height = getHeight();
            batch.setColor(tint.r, tint.g, tint.b, alpha);
            if (!isDisabled() && (focused || UiChoice.this.hasKeyboardFocus())) {
                float ring = UiTheme.CHOICE_RING;
                focusRing.draw(batch, x - ring, y - ring, width + 2 * ring, height + 2 * ring);
            }
            int state = state();
            faces[state].draw(batch, x, y, width, height);
            boolean on = state >= 4;
            if (kind != Kind.SWITCH && !on) {
                batch.setColor(Color.WHITE);
                return;
            }
            Color color = on ? colors.mark : isDisabled() ? colors.disabledMark : colors.thumb;
            batch.setColor(color.r, color.g, color.b, color.a * alpha);
            switch (kind) {
                case CHECK -> {
                    Drawable glyph = mixed ? dash : mark;
                    float size = height - 2;
                    glyph.draw(batch, x + (width - size) / 2, y + (height - size) / 2, size, size);
                }
                case RADIO -> {
                    float dot = UiTheme.RADIO_DOT;
                    mark.draw(batch, x + (width - dot) / 2, y + (height - dot) / 2, dot, dot);
                }
                case SWITCH -> {
                    float inset = UiTheme.SWITCH_INSET;
                    float thumb = height - 2 * inset;
                    mark.draw(batch, on ? x + width - inset - thumb : x + inset, y + inset, thumb, thumb);
                }
            }
            batch.setColor(Color.WHITE);
        }
    }
}
