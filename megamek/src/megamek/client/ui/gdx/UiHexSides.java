/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.function.IntConsumer;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup;
import com.badlogic.gdx.utils.Align;

/** A north-up hex with clickable sides, numbered clockwise from N. The owning view supplies the selected mask. */
public final class UiHexSides extends WidgetGroup {
    private static final String[] DIRECTIONS = {"N", "NE", "SE", "S", "SW", "NW"};
    private final Texture white;
    private final Label[] labels = new Label[6];
    private final Vector2[] corners = new Vector2[6];
    private final float[] band = new float[20];
    private int selected;
    /** Corners at which two chosen sides are drawn apart: bit s for the corner after side s, clockwise. */
    private int parted;
    private int hover = -1;
    private UiTheme.PickerStyle colors;
    private final UiKit ui;

    public UiHexSides(UiKit ui, int selected, IntConsumer toggle) {
        this.selected = selected;
        this.ui = ui;
        colors = ui.skin.get(UiTheme.PickerStyle.class);
        white = ui.skin.get("white", Texture.class);
        setTransform(false);
        for (int side = 0; side < 6; side++) {
            corners[side] = new Vector2();
            labels[side] = ui.label(DIRECTIONS[side], "hud-small", 12, UiTheme.MUTED);
            labels[side].setAlignment(Align.center);
            labels[side].setTouchable(Touchable.disabled);
            addActor(labels[side]);
        }
        addListener(new InputListener() {
            private int pressed = -1;
            @Override public boolean mouseMoved(InputEvent event, float x, float y) { hover = sideAt(x, y); return true; }
            @Override public void exit(InputEvent event, float x, float y, int pointer, Actor toActor) { hover = -1; }
            @Override public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                if (button != Input.Buttons.LEFT) { return false; }
                pressed = sideAt(x, y);
                return pressed >= 0;
            }
            @Override public void touchUp(InputEvent event, float x, float y, int pointer, int button) {
                int side = sideAt(x, y);
                if (pressed >= 0 && side == pressed) { toggle.accept(side); }
                pressed = -1;
            }
        });
    }

    public void selected(int mask) { selected = mask; }
    /**
     * Draws chosen sides apart at these corners (bit s: the corner between side s and the next, clockwise), where
     * chosen sides side by side otherwise run on as one band: doors that are not one opening, say.
     */
    public UiHexSides parted(int corners) { parted = corners; return this; }
    public int selected() { return selected; }

    /** Draws the hex in the skin's {@link UiTheme.PickerStyle} {@code name}: default (hud-v3) or token. */
    public UiHexSides style(String name) {
        colors = ui.skin.get(name, UiTheme.PickerStyle.class);
        return this;
    }

    @Override public float getPrefWidth() { return 216; }
    @Override public float getPrefHeight() { return 170; }
    @Override public float getMinWidth() { return 160; }

    @Override public void layout() {
        float radius = Math.max(1, Math.min(getWidth() - 48, getHeight() - 40) / 2);
        for (int side = 0; side < 6; side++) {
            double angle = Math.toRadians(120 - side * 60);
            corners[side].set((float) Math.cos(angle) * radius, (float) Math.sin(angle) * radius);
        }
        for (int side = 0; side < 6; side++) {
            Vector2 a = corners[side], b = corners[(side + 1) % 6];
            labels[side].setBounds(getWidth() / 2 + (a.x + b.x) * .67f - 17,
                  getHeight() / 2 + (a.y + b.y) * .67f - 11, 34, 22);
        }
    }

    /** Hit targets include the edge's surrounding band and its direction label, never the hex centre. */
    public int sideAt(float x, float y) {
        validate();
        int nearest = -1;
        float distance = 14;
        for (int side = 0; side < 6; side++) {
            Label label = labels[side];
            if (x >= label.getX() && x <= label.getX() + label.getWidth()
                  && y >= label.getY() && y <= label.getY() + label.getHeight()) { return side; }
            Vector2 a = corners[side], b = corners[(side + 1) % 6];
            float next = Intersector.distanceSegmentPoint(a.x, a.y, b.x, b.y, x - getWidth() / 2, y - getHeight() / 2);
            if (next < distance) { distance = next; nearest = side; }
        }
        return nearest;
    }

    @Override public Actor hit(float x, float y, boolean touchable) {
        if (!isVisible() || touchable && getTouchable() == Touchable.disabled) { return null; }
        return sideAt(x, y) < 0 ? null : this;
    }

    @Override public void draw(Batch batch, float parentAlpha) {
        validate();
        float previous = batch.getPackedColor(), alpha = parentAlpha * getColor().a;
        float cx = getX() + getWidth() / 2, cy = getY() + getHeight() / 2;
        for (int side = 0; side < 6; side++) {
            Vector2 a = corners[side], b = corners[(side + 1) % 6];
            boolean chosen = (selected & (1 << side)) != 0;
            Color color = chosen ? colors.accent : hover == side ? colors.hover : colors.line;
            if (chosen && (parted & (1 << (side + 5) % 6 | 1 << side)) != 0) {
                // Short of a corner it is parted at.
                Vector2 start = (parted & 1 << (side + 5) % 6) != 0 ? new Vector2(a).lerp(b, .14f) : a;
                b = (parted & 1 << side) != 0 ? new Vector2(b).lerp(a, .14f) : b;
                a = start;
            }
            if (chosen || hover == side) {
                float packed = Color.toFloatBits(color.r, color.g, color.b, alpha * (hover == side ? .28f : .16f));
                point(0, cx + a.x * .86f, cy + a.y * .86f, packed);
                point(1, cx + a.x * 1.13f, cy + a.y * 1.13f, packed);
                point(2, cx + b.x * 1.13f, cy + b.y * 1.13f, packed);
                point(3, cx + b.x * .86f, cy + b.y * .86f, packed);
                batch.draw(white, band, 0, band.length);
            }
            batch.setColor(color.r, color.g, color.b, alpha);
            float line = chosen ? 4 : hover == side ? 3 : 1.5f;
            batch.draw(white, cx + a.x, cy + a.y - line / 2, 0, line / 2,
                  a.dst(b), line, 1, 1, (float) Math.toDegrees(Math.atan2(b.y - a.y, b.x - a.x)), 0, 0, 1, 1, false, false);
            labels[side].setColor(hover == side ? colors.hover : chosen ? colors.labelOn : colors.label);
        }
        batch.setPackedColor(previous);
        super.draw(batch, parentAlpha);
    }

    private void point(int index, float x, float y, float color) {
        int offset = index * 5;
        band[offset] = x; band[offset + 1] = y; band[offset + 2] = color;
        band[offset + 3] = .5f; band[offset + 4] = .5f;
    }
}
