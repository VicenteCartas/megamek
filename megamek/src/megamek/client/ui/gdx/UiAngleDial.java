/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.function.DoubleConsumer;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.ui.Widget;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.TransformDrawable;

/**
 * A reusable angle input in degrees that reads as a compass: zero points up and positive turns clockwise. A straight
 * drag turns it, on the dial or from a caption: right and up add, so the needle follows the hand clockwise; left and
 * down subtract; across the turn boundary. A click on the dial points it there. It looks like MekBay's dials in this kit's colors: a scale around a recessed well, the turn from
 * zero along the well's rim, and the value in a dark hub, which the owner lays over the dial.
 */
public final class UiAngleDial extends Widget {
    /** The diameters of the face, the well, the hub and the needle's grip, and a stroke's height, in stage units. */
    static final float SIZE = 132, WELL = 92, HUB = 56, KNOB = 12, STROKE = 3;
    /** A half turn takes 360 units of drag. */
    private static final double DEGREES_PER_UNIT = .5;
    /** Where zero points on the face, in degrees counter-clockwise from the right: straight up. Values turn back. */
    private static final double UP = 90;
    private Drawable face, well, hub, knob;
    private UiTheme.PickerStyle colors;
    private final UiKit ui;
    private final TransformDrawable stroke;
    private final DoubleConsumer preview;
    private final UiCursorCapture cursor = new UiCursorCapture();
    private double value;
    private float originX, originY;
    private double start;

    public UiAngleDial(UiKit ui, DoubleConsumer preview, Runnable commit) {
        this.preview = preview;
        this.ui = ui;
        style("default");
        stroke = (TransformDrawable) ui.skin.getDrawable("dial-stroke");
        addListener(new InputListener() {
            /** The angle a click points to, none from the hub. */
            double aim;
            boolean dragged;
            @Override public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                if (button != Input.Buttons.LEFT || pointer != 0) { return false; }
                float dx = x - getWidth() / 2, dy = y - getHeight() / 2;
                aim = Math.hypot(dx, dy) < HUB / 2 ? Double.NaN : UP - Math.toDegrees(Math.atan2(dy, dx));
                dragged = false;
                begin(event.getStageX(), event.getStageY(), value);
                cursor.capture(event.getStageX(), event.getStageY(), event.getStage());
                return true;
            }
            @Override public void touchDragged(InputEvent event, float x, float y, int pointer) {
                Vector2 at = cursor.trusted(event.getStageX(), event.getStageY());
                dragged |= Math.hypot(at.x - originX, at.y - originY) > 3;
                if (dragged) { drag(at.x, at.y); }
            }
            @Override public void touchUp(InputEvent event, float x, float y, int pointer, int button) {
                cursor.release();
                if (!dragged && !event.isTouchFocusCancel() && Double.isFinite(aim)) { preview.accept(aim); }
                commit.run();
            }
        });
    }

    public void value(double next) { value = next; }

    /** Draws the dial in the skin's {@link UiTheme.PickerStyle} {@code name}: default (hud-v3) or token. */
    public UiAngleDial style(String name) {
        colors = ui.skin.get(name, UiTheme.PickerStyle.class);
        face = ui.skin.getDrawable(colors.discs + "-face");
        well = ui.skin.getDrawable(colors.discs + "-well");
        hub = ui.skin.getDrawable(colors.discs + "-hub");
        knob = ui.skin.getDrawable(colors.discs + "-knob");
        return this;
    }

    /** A drag from a caption turns the dial as well, from the value at its press. */
    public void begin(float stageX, float stageY, double initial) {
        originX = stageX;
        originY = stageY;
        start = initial;
    }

    public void drag(float stageX, float stageY) {
        preview.accept(start + (stageX - originX + stageY - originY) * DEGREES_PER_UNIT);
    }

    @Override public float getPrefWidth() { return SIZE; }
    @Override public float getPrefHeight() { return SIZE; }

    @Override public void draw(Batch batch, float parentAlpha) {
        float saved = batch.getPackedColor();
        float cx = getX() + getWidth() / 2, cy = getY() + getHeight() / 2;
        batch.setColor(1, 1, 1, parentAlpha);
        disc(batch, face, cx, cy, SIZE);
        disc(batch, well, cx, cy, WELL);
        // The scale: every 10 degrees, longer and brighter every 30, zero in mint.
        for (int degrees = 0; degrees < 360; degrees += 10) {
            boolean major = degrees % 30 == 0;
            Color color = degrees == 0 ? colors.accent : major ? colors.major : colors.minor;
            ray(batch, color, parentAlpha, cx, cy, UP + degrees, major ? 51 : 56, SIZE / 2 - 5, major ? 2 : 1.5f);
        }
        // The turn from zero along the well's rim, in pieces short enough to follow the circle.
        float rim = WELL / 2;
        double up = Math.toRadians(UP), sweep = Math.toRadians(value);
        int pieces = (int) Math.ceil(Math.abs(sweep) * rim / 5);
        for (int i = 0; i < pieces; i++) {
            double a = up - sweep * i / pieces, b = up - sweep * (i + 1) / pieces;
            line(batch, colors.accent, parentAlpha, cx + rim * (float) Math.cos(a), cy + rim * (float) Math.sin(a),
                  cx + rim * (float) Math.cos(b), cy + rim * (float) Math.sin(b), 3);
        }
        ray(batch, colors.accent, parentAlpha, cx, cy, UP - value, HUB / 2, rim, 2.5f);
        batch.setColor(1, 1, 1, parentAlpha);
        double angle = up - sweep;
        disc(batch, knob, cx + rim * (float) Math.cos(angle), cy + rim * (float) Math.sin(angle), KNOB);
        disc(batch, hub, cx, cy, HUB);
        batch.setPackedColor(saved);
    }

    /** The theme's discs keep their round edge only at the diameter they were rasterised at. */
    private static void disc(Batch batch, Drawable drawable, float x, float y, float diameter) {
        drawable.draw(batch, x - diameter / 2, y - diameter / 2, diameter, diameter);
    }

    private void ray(Batch batch, Color color, float alpha, float cx, float cy, double degrees, float from, float to,
          float width) {
        float x = (float) Math.cos(Math.toRadians(degrees)), y = (float) Math.sin(Math.toRadians(degrees));
        line(batch, color, alpha, cx + from * x, cy + from * y, cx + to * x, cy + to * y, width);
    }

    /**
     * The theme's round-ended stroke, turned and thinned to the segment, so its edges stay smooth at any angle. Its
     * caps centre on the ends, so the pieces of an arc join without a waist.
     */
    private void line(Batch batch, Color color, float alpha, float ax, float ay, float bx, float by, float width) {
        batch.setColor(color.r, color.g, color.b, color.a * alpha);
        stroke.draw(batch, ax - STROKE / 2, ay - STROKE / 2, STROKE / 2, STROKE / 2,
              (float) Math.hypot(bx - ax, by - ay) + STROKE, STROKE, 1, width / STROKE,
              (float) Math.toDegrees(Math.atan2(by - ay, bx - ax)));
    }
}
