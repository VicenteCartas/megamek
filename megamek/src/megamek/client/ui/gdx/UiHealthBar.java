/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's Xbox Game Studios Game Content Usage Rules
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */
package megamek.client.ui.gdx;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.ui.Widget;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.FocusListener;
import com.badlogic.gdx.utils.Align;
import megamek.client.ui.design.DesignTokens;

/**
 * A health bar for a number from 0 to its most, such as a location's armor (docs/design-system/tokens.md, Steppers):
 * − bar + then the change from the value it was made with, the value as a field and "/ most". A click on the bar
 * sets the value there and a drag scrubs it, so taking 30 points off is one gesture; the wheel over the bar steps by
 * one, or five with Shift; the arrow keys step with the focus; − and + step by one; and the value can be typed. The
 * fill takes the paperdoll's damage tiers: the accent while whole, text.primary while lightly damaged, status.warning
 * and then status.danger; what was lost since the bar was made stays as a faint ghost of the fill. Each change by the
 * user fires a ChangeEvent; {@link #value(int)} does not.
 */
public final class UiHealthBar extends Table {
    /** Above this many points the ticks mark every ten instead of every five. */
    private static final int FIVE_TICKS_UP_TO = 60;
    private static final float DISABLED_ALPHA = .4f;

    private final int max;
    private final int original;
    private final Skin skin;
    private final Bar bar;
    private final UiButton down;
    private final UiButton up;
    private final UiTextField field;
    private final Label delta;
    private int value;
    private boolean typing;
    private boolean disabled;

    UiHealthBar(UiKit kit, int value, int max, int original) {
        this.max = Math.max(0, max);
        this.original = MathUtils.clamp(original, 0, this.max);
        skin = kit.skin;
        bar = new Bar();
        float control = DesignTokens.shipped().size().get("control.sm");
        float gap = UiStepper.gap();
        down = kit.button("button-icon" + UiTheme.COMPACT, "remove", null, null);
        up = kit.button("button-icon" + UiTheme.COMPACT, "add", null, null);
        field = kit.input("", true);
        field.setAlignment(Align.right);
        field.setTextFieldFilter(new TextField.TextFieldFilter.DigitsOnlyFilter());
        field.setMaxLength(String.valueOf(this.max).length());
        Label most = kit.typeLabel("/ " + this.max, "small", "text.secondary");
        // the row ends on the most, flush with the controls of the rows round it
        most.setAlignment(Align.right);
        delta = kit.typeLabel("", "small", "status.danger");
        delta.setAlignment(Align.right);
        // the number columns are at least three digits wide, so the bars of a form's rows line up
        String digits = "0".repeat(Math.max(3, String.valueOf(this.max).length()));
        add(down).size(control);
        add(bar).growX().height(control).padLeft(gap).padRight(gap);
        add(up).size(control);
        add(delta).width(width(kit, "\u2212" + digits)).padLeft(gap);
        add(field).width(width(kit, digits) + 2 * space("lg")).height(control).padLeft(gap);
        add(most).width(width(kit, "/ " + digits)).padLeft(gap);
        down.addListener(stepping(-1));
        up.addListener(stepping(1));
        field.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                event.stop();
                String text = field.getText();
                if (!text.isEmpty()) {
                    typing = true;
                    set(Integer.parseInt(text), true);
                    typing = false;
                }
            }
        });
        field.addListener(new FocusListener() {
            @Override
            public void keyboardFocusChanged(FocusEvent event, Actor actor, boolean focused) {
                if (!focused) {
                    show();
                }
            }
        });
        value(value);
    }

    /** The value. */
    public int value() {
        return value;
    }

    /** Sets the value, brought within 0 and the most, without a ChangeEvent. */
    public void value(int next) {
        value = MathUtils.clamp(next, 0, max);
        show();
    }

    /** The most. */
    public int max() {
        return max;
    }

    /** Holds the bar where it is, such as an ammunition bin that is hit: no input, dimmed. */
    public void disabled(boolean disabled) {
        this.disabled = disabled;
        setTouchable(disabled ? Touchable.disabled : Touchable.childrenOnly);
        field.setDisabled(disabled);
        bar.setColor(1, 1, 1, disabled ? DISABLED_ALPHA : 1);
        show();
    }

    public boolean disabled() {
        return disabled;
    }

    /** The bar itself, to click, drag and wheel through the stage. */
    public Actor bar() {
        return bar;
    }

    /** The − button. */
    public UiButton down() {
        return down;
    }

    /** The + button. */
    public UiButton up() {
        return up;
    }

    /** The typed value. */
    public UiTextField field() {
        return field;
    }

    /** The change from the value the bar was made with, such as "−12"; empty when there is none. */
    public Label delta() {
        return delta;
    }

    /** The fill's colour role for {@code value} of {@code max}: the paperdoll's damage tiers. */
    public static String role(int value, int max) {
        if (max <= 0 || value >= max) {
            return "accent.primary";
        }
        float ratio = value / (float) max;
        return ratio > .7f ? "text.primary" : ratio > .4f ? "status.warning" : "status.danger";
    }

    private void set(int next, boolean user) {
        if (disabled && user) {
            show();
            return;
        }
        int clamped = MathUtils.clamp(next, 0, max);
        if (clamped == value) {
            show();
            return;
        }
        value = clamped;
        show();
        if (user) {
            fire(new ChangeListener.ChangeEvent());
        }
    }

    private void show() {
        if (!typing) {
            field.setText(String.valueOf(value));
            field.setCursorPosition(field.getText().length());
        }
        down.setDisabled(disabled || value <= 0);
        up.setDisabled(disabled || value >= max);
        int change = value - original;
        delta.setText(change == 0 ? "" : change < 0 ? "\u2212" + -change : "+" + change);
        delta.setColor(UiTheme.tokenColor(change < 0 ? "status.danger" : "status.success"));
    }

    private ChangeListener stepping(int step) {
        return new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                event.stop();
                set(value + step, true);
            }
        };
    }

    private static float width(UiKit kit, String text) {
        return (float) Math.ceil(kit.typeLabel(text, "body", "text.primary").getPrefWidth()) + space("md");
    }

    private static float space(String token) {
        return DesignTokens.shipped().space().get(token);
    }

    /** The bar: the token track, the lost share's ghost, the fill to the value, the ticks and the pointer's mark. */
    private final class Bar extends Widget {
        private final Drawable track;
        private final Drawable pill;
        private final Drawable white;
        /** The pointer's x over the bar, or negative. */
        private float hover = -1;

        private Bar() {
            track = skin.getDrawable("token-track");
            pill = skin.getDrawable("token-bar");
            white = skin.getDrawable("white");
            setTouchable(Touchable.enabled);
            UiKit.wheelWhileHovered(this);
            addListener(new InputListener() {
                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                    if (button != Input.Buttons.LEFT || max <= 0) {
                        return false;
                    }
                    if (getStage() != null) {
                        getStage().setKeyboardFocus(Bar.this);
                        // a scrolling pane round the bar takes the wheel on any press inside it; keep it here
                        getStage().setScrollFocus(Bar.this);
                    }
                    set(at(x), true);
                    return true;
                }

                @Override
                public void touchDragged(InputEvent event, float x, float y, int pointer) {
                    hover = MathUtils.clamp(x, 0, getWidth());
                    set(at(x), true);
                }

                @Override
                public boolean mouseMoved(InputEvent event, float x, float y) {
                    hover = x;
                    return false;
                }

                @Override
                public void exit(InputEvent event, float x, float y, int pointer, Actor toActor) {
                    if (pointer == -1) {
                        hover = -1;
                    }
                }

                @Override
                public boolean scrolled(InputEvent event, float x, float y, float amountX, float amountY) {
                    if (amountY == 0) {
                        return false;
                    }
                    set(value - (int) Math.signum(amountY) * step(), true);
                    return true;
                }

                @Override
                public boolean keyDown(InputEvent event, int keycode) {
                    return switch (keycode) {
                        case Input.Keys.LEFT, Input.Keys.DOWN -> key(-step());
                        case Input.Keys.RIGHT, Input.Keys.UP -> key(step());
                        case Input.Keys.HOME -> key(-max);
                        case Input.Keys.END -> key(max);
                        default -> false;
                    };
                }
            });
        }

        private boolean key(int change) {
            set(value + change, true);
            return true;
        }

        private int step() {
            return Gdx.input != null && (Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT)
                  || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT)) ? 5 : 1;
        }

        /** The value under {@code x}: the nearest whole point. */
        private int at(float x) {
            return Math.round(MathUtils.clamp(x / Math.max(1, getWidth()), 0, 1) * max);
        }

        @Override
        public float getPrefWidth() {
            return 4 * space("xxxl");
        }

        @Override
        public float getPrefHeight() {
            return DesignTokens.shipped().size().get("control.sm");
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            validate();
            float previous = batch.getPackedColor();
            float alpha = getColor().a * parentAlpha;
            float thick = space("lg");
            float y = getY() + (getHeight() - thick) / 2;
            float x = getX();
            float width = getWidth();
            batch.setColor(1, 1, 1, alpha);
            track.draw(batch, x, y, width, thick);
            if (max > 0) {
                Color fill = UiTheme.tokenColor(role(value, max));
                if (original > value) {
                    batch.setColor(fill.r, fill.g, fill.b, fill.a * alpha * .25f);
                    pill.draw(batch, x, y, Math.max(thick, width * original / max), thick);
                }
                if (value > 0) {
                    batch.setColor(fill.r, fill.g, fill.b, fill.a * alpha);
                    pill.draw(batch, x, y, Math.max(thick, width * value / max), thick);
                }
                Color gap = UiTheme.tokenColor("surface.panel");
                batch.setColor(gap.r, gap.g, gap.b, gap.a * alpha);
                int every = max > FIVE_TICKS_UP_TO ? 10 : 5;
                for (int tick = every; tick < max; tick += every) {
                    white.draw(batch, x + width * tick / max - .5f, y, 1, thick);
                }
                if (hover >= 0 && hover <= width) {
                    Color mark = UiTheme.tokenColor("text.primary");
                    batch.setColor(mark.r, mark.g, mark.b, mark.a * alpha);
                    white.draw(batch, x + width * at(hover) / max - 1, getY() + space("xs"), 2,
                          getHeight() - 2 * space("xs"));
                }
            }
            if (hasKeyboardFocus()) {
                Color ring = UiTheme.tokenColor("focus.ring");
                batch.setColor(ring.r, ring.g, ring.b, ring.a * alpha);
                white.draw(batch, x, y - space("xxs") - 1, width, 1);
                white.draw(batch, x, y + thick + space("xxs"), width, 1);
            }
            batch.setPackedColor(previous);
        }
    }
}
