/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Stack;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.FocusListener;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.TimeUtils;

/** Caption scrubbing, a click-open slider, a double-click reset, and direct typing with the same commit boundary. */
public final class UiNumber extends Table {
    /** The longest pause between the two clicks of a double-click, as libGDX's ClickListener counts taps. */
    private static final long DOUBLE_CLICK_MILLIS = 400;
    private final UiKit ui;
    private final Cell<UiKit.Checkbox> checkboxCell;
    private final TextField field;
    private final UiPopover popup;
    private final Slider slider;
    private final Label readout;
    private final Label caption;
    private final String label;
    private UiAngleDial dial;
    private final BiConsumer<String, Boolean> change;
    private final double step;
    private final double min, max;
    private double value;
    private boolean held;
    /** When the last plain caption click ended; a committed edit clears it, so it never pairs across one. */
    private long clicked;
    private final UiCursorCapture cursor = new UiCursorCapture();

    /**
     * {@code min} and {@code max} bound scrubbing and the slider, each gesture widened only to include the value it
     * starts from; typing is not limited to them. A caption double-click resets to the value nearest zero in them.
     */
    public UiNumber(UiKit ui, Stage stage, String name, double initial, double min, double max, double step,
          BiConsumer<String, Boolean> change) {
        this.ui = ui;
        this.change = change;
        this.step = step;
        this.min = min;
        this.max = max;
        label = name;
        value = initial;
        checkboxCell = add((UiKit.Checkbox) null);
        caption = ui.label(name + "  ↔", "hud-small", 12, UiTheme.TEXT);
        caption.setName("editor-scrub-" + name);
        caption.setEllipsis(true);
        add(caption).growX().minWidth(0).padRight(8);
        field = new TextField(format(initial), ui.skin, "hud");
        field.setName("editor-" + name);
        add(field).width(84).height(29);
        slider = new Slider((float) Math.min(min, initial), (float) Math.max(max, initial), (float) step, false, ui.sliderStyle());
        slider.setName("editor-slider-" + name);
        slider.setValue((float) initial);
        new UiSliderInput(slider, this::finish);
        readout = ui.label(format(initial), "hud-small", 14, UiTheme.TEXT);
        readout.setAlignment(Align.right);
        readout.setName("editor-slider-value-" + name);
        popup = new UiPopover(ui).header(name, UiKit.text("GpuBoard.number.hint"));
        popup.setName("editor-number-popup");
        Table content = new Table(); content.pad(10, 14, 10, 14);
        content.add(slider).width(188).padRight(12);
        content.add(readout).minWidth(60).right();
        popup.content(content);
        stage.addActor(popup);
        UiKit.onChange(slider, () -> preview(slider.getValue()));
        slider.addListener(new InputListener() {
            @Override public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                return pointer == 0 && button == Input.Buttons.LEFT && !slider.isDisabled();
            }
            @Override public void touchUp(InputEvent event, float x, float y, int pointer, int button) {
                finish();
                if (event.isTouchFocusCancel()) { popup.cancel(); }
            }
        });
        caption.addListener(new InputListener() {
            float origin;
            float originY;
            double start;
            boolean dragged;
            @Override public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                if (button != Input.Buttons.LEFT || pointer != 0 || held) { return false; }
                stage.setKeyboardFocus(null);
                origin = event.getStageX(); originY = event.getStageY(); start = value; dragged = false; held = true;
                syncSlider(value, true);
                popup.showAbove(caption, 0);
                if (dial != null) { dial.begin(event.getStageX(), event.getStageY(), value); }
                cursor.capture(event.getStageX(), event.getStageY(), event.getStage());
                return true;
            }
            @Override public void touchDragged(InputEvent event, float x, float y, int pointer) {
                if (!held) { return; }
                Vector2 at = cursor.trusted(event.getStageX(), event.getStageY());
                float distance = at.x - origin;
                if (Math.hypot(distance, at.y - originY) > 3) { dragged = true; }
                if (dragged) {
                    if (dial != null) { dial.drag(at.x, at.y); return; }
                    double next = Math.round((start + distance * step / 4) / step) * step;
                    next = MathUtils.clamp((float) next, slider.getMinValue(), slider.getMaxValue());
                    syncSlider(next, false); preview(next);
                }
            }
            @Override public void touchUp(InputEvent event, float x, float y, int pointer, int button) {
                if (!held) { return; }
                held = false;
                cursor.release();
                if (dragged) { finish(); popup.cancel(); }
                else if (event.isTouchFocusCancel()) { popup.cancel(); }
                else if (TimeUtils.timeSinceMillis(clicked) < DOUBLE_CLICK_MILLIS) { resetValue(); popup.cancel(); }
                else { clicked = TimeUtils.millis(); }
            }
        });
        field.addListener(new FocusListener() {
            @Override public void keyboardFocusChanged(FocusEvent event, com.badlogic.gdx.scenes.scene2d.Actor actor, boolean focused) {
                if (!focused) { typed(); }
            }
        });
        field.addListener(new InputListener() {
            @Override public boolean keyDown(InputEvent event, int key) {
                if (key == Input.Keys.ENTER || key == Input.Keys.NUMPAD_ENTER) { stage.setKeyboardFocus(null); return true; }
                if (key == Input.Keys.ESCAPE) { field.setText(format(value)); stage.setKeyboardFocus(null); return true; }
                return false;
            }
        });
    }

    private void preview(double next) {
        next = normalized(next);
        if (Math.abs(next - value) < step * .001) { return; }
        value = next; showValue(); change.accept(format(next), false);
    }
    private void finish() { clicked = 0; change.accept(format(value), true); }
    /** Commits the value nearest zero in the configured range once, as typing it would. */
    private void resetValue() {
        clicked = 0;
        double next = normalized(MathUtils.clamp(0, min, max));
        if (next != value) { value = next; showValue(); finish(); }
    }
    private void typed() {
        double previous = value;
        try {
            double next = Double.parseDouble(field.getText().trim());
            if (!Double.isFinite(next) || step >= 1 && next != Math.rint(next)) { throw new NumberFormatException(); }
            next = normalized(next);
            if (next != value) { value = next; finish(); }
        } catch (IllegalArgumentException invalid) { value = previous; }
        showValue();
    }
    private void showValue() {
        String shown = format(value);
        field.setText(shown);
        readout.setText(dial == null ? shown : shown + "°");
        if (dial != null) { dial.value(value); }
    }
    /**
     * Silence only our own updates: libGDX uses setValue for pointer drags too. The range grows to include
     * {@code next}; a {@code restart} grows it from min and max, so an earlier value outside them no longer widens it.
     */
    private void syncSlider(double next, boolean restart) {
        slider.setProgrammaticChangeEvents(false);
        try {
            slider.setRange((float) Math.min(restart ? min : slider.getMinValue(), next),
                  (float) Math.max(restart ? max : slider.getMaxValue(), next));
            slider.setValue((float) next);
        } finally { slider.setProgrammaticChangeEvents(true); }
    }
    public boolean editing() { return held || popup.isVisible() || field.hasKeyboardFocus(); }
    /**
     * An angle wraps at +/-180 degrees and uses the kit's dial, with the value in its hub. A straight drag turns it,
     * from the caption or on the dial: right and up add, left and down subtract.
     */
    public UiNumber angle() {
        dial = new UiAngleDial(ui, next -> preview(Math.round(next / step) * step), () -> { finish(); popup.cancel(); });
        dial.setName("editor-dial-" + label);
        caption.setText(label + "  ↻");
        readout.setStyle(new Label.LabelStyle(ui.skin.getFont("hud-heading"), UiTheme.TEXT));
        UiKit.size(readout, "hud-heading", 20);
        Container<Label> hub = new Container<>(readout);
        hub.setTouchable(Touchable.disabled);
        Table content = new Table(); content.pad(8, 14, 10, 14);
        content.add(new Stack(dial, hub)).size(UiAngleDial.SIZE);
        popup.header(UiKit.text("GpuBoard.number.rotation", label), UiKit.text("GpuBoard.number.angleHint")).content(content);
        value = normalized(value); showValue();
        return this;
    }
    private double normalized(double next) {
        return dial == null ? next : next - 360 * Math.floor((next + 180) / 360);
    }
    /** Optional application toggle; typing and caption scrubbing remain available while it is unchecked. */
    public UiNumber withCheckbox(boolean checked, Consumer<Boolean> changed) {
        UiKit.Checkbox checkbox = ui.checkbox("", checked);
        checkbox.setName(getName() == null ? "number-enabled" : getName() + "-enabled");
        UiKit.onChange(checkbox, () -> changed.accept(checkbox.isTicked()));
        checkboxCell.setActor(checkbox).width(26);
        invalidateHierarchy();
        return this;
    }
    /** Size the value field for the space available; caption gestures and the slider stay the same. */
    public UiNumber fieldSize(float width, float height) {
        getCell(field).size(width, height);
        return this;
    }

    /**
     * The Token v0 look: a text.secondary caption, the compact token input with its hover edge, the token slider and,
     * for an angle, the token dial. Call it after {@link #angle} and {@link #withCheckbox}.
     */
    public UiNumber tokenLook() {
        Label.LabelStyle secondary = new Label.LabelStyle(caption.getStyle());
        secondary.fontColor = UiTheme.tokenColor("text.secondary");
        caption.setStyle(secondary);
        field.setStyle(ui.skin.get("input" + UiTheme.COMPACT, TextField.TextFieldStyle.class));
        UiKit.hoverEdge(field, field);
        getCell(field).height(field.getStyle().background.getMinHeight());
        slider.setStyle(ui.tokenSliderStyle());
        if (dial != null) {
            dial.style(UiTheme.TOKEN);
        }
        return this;
    }
    /** Axis triplets share their rotation heading, so each caption can be just X, Y or Z. */
    public UiNumber compactCaption() {
        caption.setText(label); getCell(caption).padRight(4); return this;
    }
    /** Refresh from an authoritative snapshot without interrupting typing or a drag, or firing a command. */
    public void value(double next) {
        if (editing() || value == next) { return; }
        value = normalized(next);
        showValue();
        syncSlider(next, true);
    }

    /** Give reusable controls stable names in their owner's namespace. */
    public UiNumber names(String id) {
        setName(id);
        caption.setName(id + "-caption");
        field.setName(id + "-field");
        slider.setName(id + "-slider");
        readout.setName(id + "-slider-value");
        popup.setName(id + "-popup");
        if (dial != null) { dial.setName(id + "-dial"); }
        return this;
    }
    public void dismiss() {
        if (held && getStage() != null) { getStage().cancelTouchFocus(caption); }
        // A drag on the dial ends too, so a hidden dial never keeps the cursor.
        if (dial != null && getStage() != null) { getStage().cancelTouchFocus(dial); }
        popup.cancel();
    }
    @Override protected void setStage(Stage stage) {
        if (getStage() != null && getStage() != stage) { dismiss(); }
        super.setStage(stage);
    }
    public void close() { dismiss(); popup.remove(); }
    public static String format(double value) {
        return java.math.BigDecimal.valueOf(Math.round(value * 10000) / 10000.0).stripTrailingZeros().toPlainString();
    }
}
