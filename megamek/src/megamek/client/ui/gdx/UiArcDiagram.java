/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.function.DoubleFunction;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup;
import com.badlogic.gdx.utils.Align;
import megamek.client.ui.design.DesignTokens;

/**
 * A ring of arcs around a centre picture in the Token v0 look (docs/design-system/tokens.md, Charts), such as a unit's
 * damage by firing arc: the first arc centred at the top and the rest clockwise, each a track in border.subtle filled
 * in accent.primary at an opacity from {@code tint.hover} up to full by its value against the peak, nothing for
 * nothing. The value sits on its arc in title type (text.onAccent once the fill is strong), the arc's name outside the
 * ring in caption and text.muted. {@link #reveal} fades the arcs in one after another, a {@code motion.fast} step
 * apart over {@code motion.medium} on the token easing; later values glide to their new fill over
 * {@code motion.medium}. Without motion everything shows at once.
 */
public final class UiArcDiagram extends WidgetGroup {
    /** The inner radius as a share of the outer one. */
    public static final float INNER = .55f;
    /** The fill at or over which the value turns text.onAccent. */
    public static final float STRONG = .6f;
    private static final int SLICES = 16;

    private final UiKit kit;
    private final Texture white;
    private Actor centre;
    private final Color track = new Color(UiTheme.tokenColor("border.subtle"));
    private final Color fill = new Color(UiTheme.tokenColor("accent.primary"));
    private final Color strongText = new Color(UiTheme.tokenColor("text.onAccent"));
    private final Color plainText = new Color(UiTheme.tokenColor("text.primary"));
    private final float floor;
    private final float step;
    private final float duration;
    private final float[] easing;
    private final float gap = space("xs");
    /** The space between arcs, a band of even width along the line between them. */
    private final float arcGap = space("md");
    private final float[] quad = new float[20];
    private Label[] names = new Label[0];
    private Label[] values = new Label[0];
    private float[] from = new float[0];
    private float[] target = new float[0];
    private boolean motion;
    private boolean revealing;
    private float revealed;
    private float glided = Float.MAX_VALUE;
    private float outer;
    private float inner;

    public UiArcDiagram(UiKit kit) {
        this.kit = kit;
        DesignTokens tokens = DesignTokens.shipped();
        white = kit.skin.get("white", Texture.class);
        floor = tokens.opacity().get("tint.hover");
        step = tokens.duration("fast") / 1000f;
        duration = tokens.duration("medium") / 1000f;
        easing = tokens.easing();
        setTouchable(Touchable.disabled);
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    /** Shows {@code picture} in the ring's centre, sized to fit inside it, or none for {@code null}. */
    public UiArcDiagram centre(Actor picture) {
        if (centre != null) {
            centre.remove();
        }
        centre = picture;
        if (picture != null) {
            addActor(picture);
        }
        return this;
    }

    /**
     * Shows one arc per name, first at the top and then clockwise, filled by its value against {@code peak} and
     * labelled by {@code format}. With motion the fills glide from what showed before.
     */
    public void show(String[] arcNames, float[] arcValues, float peak, DoubleFunction<String> format) {
        int count = arcNames.length;
        if (count != names.length) {
            for (Label label : names) {
                label.remove();
            }
            for (Label label : values) {
                label.remove();
            }
            names = new Label[count];
            values = new Label[count];
            from = new float[count];
            target = new float[count];
            for (int i = 0; i < count; i++) {
                names[i] = kit.typeLabel("", "caption", "text.muted");
                names[i].setAlignment(Align.center);
                values[i] = kit.typeLabel("", "title", "text.primary");
                values[i].setAlignment(Align.center);
                addActor(names[i]);
                addActor(values[i]);
            }
        }
        for (int i = 0; i < count; i++) {
            from[i] = fill(i);
            target[i] = intensity(arcValues[i], peak, floor);
            names[i].setText(arcNames[i]);
            values[i].setText(format.apply(arcValues[i]));
        }
        glided = motion ? 0 : Float.MAX_VALUE;
        invalidateHierarchy();
    }

    /** Fades the arcs in one after another from the next frame, or shows them at once without {@code motion}. */
    public UiArcDiagram reveal(boolean motion) {
        return reveal(motion, 0);
    }

    /** Fades the arcs in one after another from {@code delay} seconds on, or shows them at once without motion. */
    public UiArcDiagram reveal(boolean motion, float delay) {
        this.motion = motion;
        revealing = motion;
        revealed = -delay;
        glided = Float.MAX_VALUE;
        return this;
    }

    /** How far arc {@code index} has faded in, 0 to 1. */
    public float shown(int index) {
        if (!revealing) {
            return 1;
        }
        return UiReveal.ease(Math.max(0, Math.min(1, (revealed - UiReveal.delay(index, step)) / duration)), easing);
    }

    /** The fill of arc {@code index} now, 0 for nothing and up to 1. */
    public float fill(int index) {
        if (index >= target.length) {
            return 0;
        }
        float t = UiReveal.ease(Math.min(1, glided / duration), easing);
        return from[index] + (target[index] - from[index]) * t;
    }

    /** The value label of arc {@code index}, for tests. */
    public Label value(int index) {
        return values[index];
    }

    /** The number of arcs. */
    public int arcs() {
        return names.length;
    }

    /** The angle of arc {@code index}'s centre among {@code count}, in degrees anticlockwise from the right. */
    public static float centreAngle(int index, int count) {
        return 90 - index * 360f / count;
    }

    /**
     * The angles, in degrees and clockwise, that cut arc {@code index} among {@code count} into {@code slices}, leaving
     * half of {@code gapDegrees} free at each end.
     */
    public static float[] sliceAngles(int index, int count, float gapDegrees, int slices) {
        float half = 180f / count - gapDegrees / 2;
        float start = centreAngle(index, count) + half;
        float[] angles = new float[slices + 1];
        for (int i = 0; i <= slices; i++) {
            angles[i] = start - 2 * half * i / slices;
        }
        return angles;
    }

    /**
     * The angle, in degrees, that a gap {@code width} wide takes at {@code radius}: twice the angle at which a side of
     * an arc stands {@code width / 2} off the line between two arcs, so the gap is an even band, its sides straight and
     * parallel, rather than a wedge that widens outwards.
     */
    public static float gapDegrees(float width, float radius) {
        if (radius <= 0) {
            return 0;
        }
        return 2 * (float) Math.toDegrees(Math.asin(Math.min(1, width / 2 / radius)));
    }

    /** The fill for {@code value} against {@code peak}: 0 for nothing, else from {@code floor} up to 1 at the peak. */
    public static float intensity(double value, double peak, float floor) {
        if (value <= 0 || peak <= 0) {
            return 0;
        }
        return floor + (1 - floor) * (float) Math.min(1, value / peak);
    }

    @Override
    public float getPrefWidth() {
        return space("xxxl") * 8;
    }

    @Override
    public float getPrefHeight() {
        return space("xxxl") * 6;
    }

    @Override
    public float getMinWidth() {
        return space("xxxl") * 4;
    }

    @Override
    public float getMinHeight() {
        return space("xxxl") * 4;
    }

    @Override
    public void layout() {
        float nameWidth = 0;
        float nameHeight = 0;
        for (Label name : names) {
            nameWidth = Math.max(nameWidth, name.getPrefWidth());
            nameHeight = Math.max(nameHeight, name.getPrefHeight());
        }
        outer = Math.max(1, Math.min(getWidth() / 2 - nameWidth - gap * 2, getHeight() / 2 - nameHeight - gap * 2));
        inner = outer * INNER;
        float cx = getWidth() / 2;
        float cy = getHeight() / 2;
        float side = inner * 1.3f;
        if (centre != null) {
            centre.setBounds(cx - side / 2, cy - side / 2, side, side);
        }
        float middle = (outer + inner) / 2;
        for (int i = 0; i < names.length; i++) {
            double angle = Math.toRadians(centreAngle(i, names.length));
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            Label value = values[i];
            float width = value.getPrefWidth();
            float height = value.getPrefHeight();
            value.setBounds(cx + cos * middle - width / 2, cy + sin * middle - height / 2, width, height);
            Label name = names[i];
            width = name.getPrefWidth();
            height = name.getPrefHeight();
            float reach = outer + gap;
            name.setBounds(cx + cos * reach + cos * width / 2 - width / 2, cy + sin * reach + sin * height / 2
                  - height / 2, width, height);
        }
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        boolean moving = false;
        if (revealing) {
            revealed += delta;
            revealing = revealed < UiReveal.delay(names.length - 1, step) + duration;
            moving = true;
        }
        if (glided < duration) {
            glided += delta;
            moving = true;
        }
        if (moving && Gdx.graphics != null) {
            Gdx.graphics.requestRendering();
        }
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        validate();
        float alpha = parentAlpha * getColor().a;
        float cx = getX() + getWidth() / 2;
        float cy = getY() + getHeight() / 2;
        for (int i = 0; i < names.length; i++) {
            float shown = shown(i);
            float[] innerAngles = sliceAngles(i, names.length, gapDegrees(arcGap, inner), SLICES);
            float[] outerAngles = sliceAngles(i, names.length, gapDegrees(arcGap, outer), SLICES);
            float trackColor = Color.toFloatBits(track.r, track.g, track.b, track.a * alpha * shown);
            wedge(batch, innerAngles, outerAngles, cx, cy, trackColor);
            float level = fill(i);
            if (level > 0) {
                wedge(batch, innerAngles, outerAngles, cx, cy,
                      Color.toFloatBits(fill.r, fill.g, fill.b, fill.a * level * alpha * shown));
            }
            names[i].getColor().a = shown;
            values[i].getColor().a = shown;
            values[i].getStyle().fontColor = level >= STRONG ? strongText : plainText;
        }
        if (centre != null) {
            centre.getColor().a = (names.length == 0) ? 1 : shown(0);
        }
        super.draw(batch, parentAlpha);
    }

    /** An arc between its inner edge's angles and its outer edge's, which differ so its sides run straight. */
    private void wedge(Batch batch, float[] innerAngles, float[] outerAngles, float cx, float cy, float color) {
        for (int i = 0; i + 1 < innerAngles.length; i++) {
            double a = Math.toRadians(innerAngles[i]);
            double b = Math.toRadians(innerAngles[i + 1]);
            double c = Math.toRadians(outerAngles[i]);
            double d = Math.toRadians(outerAngles[i + 1]);
            point(0, cx + (float) Math.cos(a) * inner, cy + (float) Math.sin(a) * inner, color);
            point(1, cx + (float) Math.cos(c) * outer, cy + (float) Math.sin(c) * outer, color);
            point(2, cx + (float) Math.cos(d) * outer, cy + (float) Math.sin(d) * outer, color);
            point(3, cx + (float) Math.cos(b) * inner, cy + (float) Math.sin(b) * inner, color);
            batch.draw(white, quad, 0, quad.length);
        }
    }

    private void point(int index, float x, float y, float color) {
        int offset = index * 5;
        quad[offset] = x;
        quad[offset + 1] = y;
        quad[offset + 2] = color;
        quad[offset + 3] = .5f;
        quad[offset + 4] = .5f;
    }
}
