/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.gdx.UiReveal;

/**
 * The briefing's map in its frame, with a muted caption under it. The frame's height follows only the column's width
 * and the window's room, never the map, so it takes its full room as soon as the briefing shows: the map, baked in the
 * background, fades in inside it over {@code motion.medium} and nothing under the pointer moves. Until then the frame
 * shows empty. In a short window the frame gives way first, so the start button stays clear of the bottom row.
 */
final class StartMapFrame extends WidgetGroup {
    /** The frame's height as a share of its width, within these bounds. */
    static final float HEIGHT_SHARE = .45f;
    static final float MIN_HEIGHT = 140;
    static final float MAX_HEIGHT = 240;
    /** How far the frame gives way when the column runs short of room, before it leaves the map out. */
    static final float SHRUNK_HEIGHT = 90;
    /** Room between the frame's edge and the map. */
    private static final float INSET = 6;
    private static final float CAPTION_GAP = 4;

    private final Drawable frame;
    private final Label caption;
    private final boolean motion;
    private final float fadeSeconds;
    private final float[] easing;
    private Texture map;
    private float shownFor;

    StartMapFrame(Drawable frame, Label caption, boolean motion) {
        this.frame = frame;
        this.caption = caption;
        this.motion = motion;
        DesignTokens tokens = DesignTokens.shipped();
        fadeSeconds = tokens.duration("medium") / 1000f;
        easing = tokens.easing();
        setTransform(false);
        setTouchable(Touchable.disabled);
        caption.setEllipsis(true);
        addActor(caption);
    }

    /** The frame's height at {@code width}. */
    static float frameHeight(float width) {
        return MathUtils.clamp(width * HEIGHT_SHARE, MIN_HEIGHT, MAX_HEIGHT);
    }

    /**
     * The height the frame takes in the column: {@code preferred} when the column has room for it, else what is left
     * after {@code overflow} as long as that is at least {@code least}, else nothing, so the column leaves the map out.
     */
    static float fitted(float preferred, float least, float overflow) {
        float shrunk = preferred - Math.max(0, overflow);
        return shrunk >= least ? shrunk : 0;
    }

    /** Shows {@code next}, which this frame then owns, fading it in; the map it showed before is disposed. */
    void setMap(Texture next) {
        if (map != null) {
            map.dispose();
        }
        map = next;
        shownFor = 0;
    }

    void setCaption(String text) {
        caption.setText(text);
        invalidateHierarchy();
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        if (map != null) {
            shownFor += delta;
        }
    }

    @Override
    public float getPrefHeight() {
        return frameHeight(getWidth()) + CAPTION_GAP + caption.getPrefHeight();
    }

    /** The least room the frame still reads in; with less, the column leaves the map out. */
    @Override
    public float getMinHeight() {
        return SHRUNK_HEIGHT + CAPTION_GAP + caption.getPrefHeight();
    }

    @Override
    public float getPrefWidth() {
        return 0;
    }

    @Override
    public void layout() {
        caption.setBounds(0, 0, getWidth(), caption.getPrefHeight());
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        validate();
        Color tint = getColor();
        float alpha = tint.a * parentAlpha;
        float width = getWidth();
        float height = getHeight() - CAPTION_GAP - caption.getPrefHeight();
        if (height <= 2 * INSET) {
            return;
        }
        float bottom = getY() + getHeight() - height;
        batch.setColor(tint.r, tint.g, tint.b, alpha);
        frame.draw(batch, getX(), bottom, width, height);
        if (map != null) {
            float scale = Math.min((width - 2 * INSET) / map.getWidth(), (height - 2 * INSET) / map.getHeight());
            float mapWidth = map.getWidth() * scale;
            float mapHeight = map.getHeight() * scale;
            float shown = motion && fadeSeconds > 0 ? UiReveal.ease(Math.min(1, shownFor / fadeSeconds), easing) : 1;
            batch.setColor(1, 1, 1, alpha * shown);
            batch.draw(map, getX() + (width - mapWidth) / 2, bottom + (height - mapHeight) / 2, mapWidth, mapHeight);
        }
        batch.setColor(Color.WHITE);
        super.draw(batch, parentAlpha);
    }

    void dispose() {
        setMap(null);
    }
}
