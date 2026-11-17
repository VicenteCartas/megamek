/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Cursor;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.FocusListener;
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable;
import com.badlogic.gdx.utils.Align;
import megamek.client.ui.design.DesignTokens;

/**
 * A compact whole-number stepper: an optional caption, then − the value + ({@code button-icon} compact buttons round the
 * value in body.strong), for small counts such as a pilot's skill or how many copies to add. The caption, the buttons
 * and the value stand {@link #GAP} ({@code xs}) apart, every gap the same, so a row of steppers reads as groups when
 * they stand a larger space apart; the value's cell is as wide as the widest value, so the buttons never move. Its
 * buttons disable at the bounds; the arrow keys step it while it has the keyboard focus. A click on a number's value
 * turns it into a compact field to type the value in: Enter or leaving the field sets it, brought within the bounds,
 * and Escape keeps the old one. It fires a ChangeEvent on each step or typed value. Made by
 * {@link UiKit#stepper(int, int, int)} and {@link UiKit#stepper(String, int, int, int)}; as a selector
 * ({@link UiKit#selector(List, int)}) it shows one of a list of choices between chevrons, as a game's settings menu
 * does, its cell as wide as the widest choice, and takes no typing.
 */
public final class UiStepper extends Table {
    private final int min;
    private final int max;
    private final UiButton down;
    private final UiButton up;
    private final Label shown;
    private final Label caption;
    /** The choices a selector shows for its values, or null for a number. */
    private final List<String> choices;
    /** The field a number's value is typed in, in the value's cell while typing; {@code null} for a selector. */
    private final UiTextField field;
    private final Cell<Actor> valueCell;
    /** The field's width while typing. */
    private float fieldWidth;
    private final float valueWidth;
    private boolean typing;
    private int value;

    UiStepper(UiKit kit, String caption, int value, int min, int max) {
        this(kit, caption, value, min, max, null);
    }

    /** A selector: one of {@code choices}, the {@code index}th first, between chevrons. */
    UiStepper(UiKit kit, List<String> choices, int index) {
        this(kit, null, index, 0, choices.size() - 1, List.copyOf(choices));
    }

    private UiStepper(UiKit kit, String caption, int value, int min, int max, List<String> choices) {
        this.min = min;
        this.max = Math.max(min, max);
        this.choices = choices;
        float control = DesignTokens.shipped().size().get("control.sm");
        float gap = gap();
        down = kit.button("button-icon" + UiTheme.COMPACT, choices == null ? "remove" : "chevron-left", null, null);
        up = kit.button("button-icon" + UiTheme.COMPACT, choices == null ? "add" : "chevron-right", null, null);
        shown = kit.typeLabel("", "body.strong", "text.primary");
        shown.setAlignment(Align.center);
        if (caption != null) {
            this.caption = kit.typeLabel(caption, "small", "text.secondary");
            add(this.caption).padRight(gap);
        } else {
            this.caption = null;
        }
        add(down).size(control);
        valueWidth = valueWidth(this.min, this.max);
        valueCell = add((Actor) shown).width(valueWidth).height(control).padLeft(gap).padRight(gap);
        add(up).size(control);
        down.addListener(stepping(-1));
        up.addListener(stepping(1));
        addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                if (typing) {
                    return false;
                }
                return switch (keycode) {
                    case Input.Keys.LEFT, Input.Keys.DOWN, Input.Keys.MINUS -> step(-1);
                    case Input.Keys.RIGHT, Input.Keys.UP, Input.Keys.PLUS, Input.Keys.EQUALS -> step(1);
                    default -> false;
                };
            }
        });
        field = choices == null ? typingField(kit) : null;
        value(value);
    }

    /**
     * The field a number's value is typed in, and the click on the value that opens it. It sets the value in its own
     * font, with only the {@link #gap()} of its frame round the text, so it fills the value's cell and the gaps beside it
     * and nothing round it moves unless the typed number is wider than the cell.
     */
    private UiTextField typingField(UiKit kit) {
        UiTextField typed = kit.input("", true);
        TextField.TextFieldStyle style = new TextField.TextFieldStyle(typed.getStyle());
        style.font = shown.getStyle().font;
        style.messageFont = style.font;
        style.fontColor = shown.getStyle().fontColor;
        style.focusedFontColor = style.fontColor;
        style.background = inset(style.background, gap());
        style.focusedBackground = inset(style.focusedBackground, gap());
        typed.setStyle(style);
        typed.setAlignment(Align.center);
        typed.setMaxLength(Math.max(String.valueOf(min).length(), String.valueOf(max).length()));
        typed.setTextFieldFilter((textField, character) -> Character.isDigit(character)
              || (character == '-' && min < 0 && minusFits(textField.getText(), insertionPoint(textField),
              textField.getSelection())));
        // Each key changes the field, not the value; Enter or leaving the field sets it
        typed.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (actor == typed) {
                    event.stop();
                    fitTyped();
                }
            }
        });
        typed.addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                if (keycode == Input.Keys.ENTER || keycode == Input.Keys.NUMPAD_ENTER) {
                    event.stop();
                    endTyping(true, true);
                    return true;
                }
                if (keycode == Input.Keys.ESCAPE) {
                    event.stop();
                    endTyping(false, true);
                    return true;
                }
                return false;
            }
        });
        typed.addListener(new FocusListener() {
            @Override
            public void keyboardFocusChanged(FocusEvent event, Actor actor, boolean focused) {
                if (!focused) {
                    endTyping(true, false);
                }
            }
        });
        shown.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                type();
            }

            @Override
            public void enter(InputEvent event, float x, float y, int pointer, Actor fromActor) {
                super.enter(event, x, y, pointer, fromActor);
                if (pointer == -1 && Gdx.graphics != null) {
                    Gdx.graphics.setSystemCursor(Cursor.SystemCursor.Ibeam);
                }
            }

            @Override
            public void exit(InputEvent event, float x, float y, int pointer, Actor toActor) {
                super.exit(event, x, y, pointer, toActor);
                if (pointer == -1 && Gdx.graphics != null) {
                    Gdx.graphics.setSystemCursor(Cursor.SystemCursor.Arrow);
                }
            }
        });
        return typed;
    }

    /** {@code frame} with {@code side} between its left and right edges and the text. */
    private static Drawable inset(Drawable frame, float side) {
        if (!(frame instanceof NinePatchDrawable patch)) {
            return frame;
        }
        NinePatchDrawable copy = new NinePatchDrawable(patch);
        copy.setLeftWidth(side);
        copy.setRightWidth(side);
        return copy;
    }

    /**
     * The width a text field needs to show {@code text} whole: the sum of its glyphs' advances after the first's offset,
     * as {@code TextField} lays it out. Less, and the field scrolls a whole character out of view.
     */
    private static float typedWidth(BitmapFont font, String text) {
        GlyphLayout layout = new GlyphLayout(font, text);
        float width = 0;
        for (GlyphLayout.GlyphRun run : layout.runs) {
            for (int i = 1; i < run.xAdvances.size; i++) {
                width += run.xAdvances.get(i);
            }
        }
        return width;
    }

    /** The space between the caption, the buttons and the value: {@code xs}. */
    public static float gap() {
        return DesignTokens.shipped().space().get("xs");
    }

    /** The value's cell: as wide as the widest of the values from {@code min} to {@code max}. */
    private float valueWidth(int min, int max) {
        String saved = shown.getText().toString();
        float widest = 0;
        List<String> texts = choices != null ? choices : List.of(String.valueOf(min), String.valueOf(max),
              "0".repeat(String.valueOf(max).length()));
        for (String text : texts) {
            shown.setText(text);
            // A typed value is measured as a text field does, which can be a little wider than the label
            widest = Math.max(widest, Math.max(shown.getPrefWidth(), typedWidth(shown.getStyle().font, text)));
        }
        shown.setText(saved);
        return (float) Math.ceil(widest);
    }

    /** The caption before the buttons, or null. */
    public Label caption() {
        return caption;
    }

    /** The − button, to click through the stage. */
    public UiButton down() {
        return down;
    }

    /** The + button, to click through the stage. */
    public UiButton up() {
        return up;
    }

    /** The value's label. */
    public Label shown() {
        return shown;
    }

    /** The value, within the bounds. */
    public int value() {
        return value;
    }

    /** Sets the value, brought within the bounds, without a ChangeEvent. */
    public void value(int next) {
        value = clamp(next, min, max);
        shown.setText(choices == null ? String.valueOf(value) : choices.get(value));
        down.setDisabled(value <= min);
        up.setDisabled(value >= max);
    }

    /** The field a number's value is typed in, or {@code null} for a selector; it stands in the value's cell while typing. */
    public UiTextField field() {
        return field;
    }

    /** Whether the value is being typed. */
    public boolean typing() {
        return typing;
    }

    /** Opens the field over the value, its text selected, so typing replaces it; a selector takes no typing. */
    public void type() {
        Stage stage = getStage();
        if (field == null || typing || stage == null) {
            return;
        }
        typing = true;
        field.setText(String.valueOf(value));
        valueCell.setActor(field);
        valueCell.padLeft(0).padRight(0);
        fieldWidth = 0;
        fitTyped();
        // The value under the pointer is gone, so it never sees the pointer leave
        if (Gdx.graphics != null) {
            Gdx.graphics.setSystemCursor(Cursor.SystemCursor.Arrow);
        }
        field.selectAll();
        stage.setKeyboardFocus(field);
    }

    /**
     * Sizes the field to its text: the field takes the gaps beside the value as its frame, so the stepper keeps its
     * width while the text fits the value's cell, and grows only when the typed number needs more room.
     */
    private void fitTyped() {
        if (!typing) {
            return;
        }
        float needed = (float) Math.ceil(typedWidth(field.getStyle().font, field.getText()));
        float width = Math.max(valueWidth, needed) + 2 * gap();
        if (width != fieldWidth) {
            fieldWidth = width;
            valueCell.width(width);
            invalidateHierarchy();
        }
    }

    /**
     * Closes the field; {@code keep} sets the typed value, firing a ChangeEvent if it changed, else drops it.
     * {@code refocus}, by a key in the field, hands the keyboard focus back to the stepper, so the arrow keys step it.
     */
    private void endTyping(boolean keep, boolean refocus) {
        if (!typing) {
            return;
        }
        typing = false;
        String text = field.getText().trim();
        Stage stage = getStage();
        if (refocus && stage != null) {
            stage.setKeyboardFocus(this);
        }
        valueCell.setActor(shown);
        valueCell.width(valueWidth).padLeft(gap()).padRight(gap());
        invalidateHierarchy();
        if (keep) {
            Integer typed = UiRangeInput.parse(text);
            if (typed != null && clamp(typed, min, max) != value) {
                value(typed);
                fire(new ChangeListener.ChangeEvent());
            }
        }
    }

    /** Where a typed key goes in {@code field}: the start of its selection, or its cursor. */
    private static int insertionPoint(com.badlogic.gdx.scenes.scene2d.ui.TextField field) {
        return field.getSelection().isEmpty() ? field.getCursorPosition()
              : Math.min(field.getSelectionStart(), field.getCursorPosition());
    }

    /** Whether a minus typed at {@code at} in {@code text}, replacing {@code selection}, leads a lone sign. */
    static boolean minusFits(String text, int at, String selection) {
        return at == 0 && (!text.contains("-") || selection.contains("-"));
    }

    /** {@code value} brought within {@code min} and {@code max}. */
    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Steps by {@code delta} on a button's change, which goes no further: the stepper fires its own. */
    private ChangeListener stepping(int delta) {
        return new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                event.stop();
                step(delta);
            }
        };
    }

    private boolean step(int delta) {
        int next = clamp(value + delta, min, max);
        if (next == value) {
            return false;
        }
        value(next);
        fire(new ChangeListener.ChangeEvent());
        return true;
    }
}
