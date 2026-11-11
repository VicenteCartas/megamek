/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.Random;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.scenes.scene2d.ui.Widget;

/**
 * A speaker's voice on a comm channel: a row of bars mirrored about a center line that dance in syllables, words and
 * pauses while they {@linkplain #speaking(boolean) speak} and sink to a flat line when they stop. Its level can drive a
 * {@link UiSignalFeed}'s picture ({@link #drive(UiSignalFeed)}). Without motion it shows the flat line.
 */
public class UiVoiceTrace extends Widget {
    private static final int BARS = 28;
    private static final float GAP = 2;
    /** Seconds a syllable lasts, shortest and longest; the chance a syllable is a pause between words. */
    private static final float SYLLABLE_MIN = .06f;
    private static final float SYLLABLE_MAX = .15f;
    private static final float PAUSE_CHANCE = .16f;
    /** How fast the level follows the syllables, rising and falling, a second. */
    private static final float RISE = 22;
    private static final float FALL = 9;

    private final TextureRegion white;
    private final Color color;
    private final boolean motion;
    private final float prefWidth;
    private final float prefHeight;
    private final Random random = new Random();
    private final float[] heights = new float[BARS];
    private final float[] targets = new float[BARS];
    private UiSignalFeed feed;
    private boolean speaking;
    private float syllable;
    private float target;
    private float level;

    /**
     * A trace {@code width} by {@code height} stage units in {@code color}; {@code white} is a solid white region for
     * its bars.
     */
    public UiVoiceTrace(TextureRegion white, float width, float height, Color color, boolean motion) {
        this.white = white;
        this.color = new Color(color);
        this.motion = motion;
        prefWidth = width;
        prefHeight = height;
    }

    /** Brightens {@code target}'s picture with the voice from now on. */
    public UiVoiceTrace drive(UiSignalFeed target) {
        feed = target;
        return this;
    }

    /** Starts or stops the speaker talking. */
    public void speaking(boolean value) {
        speaking = value && motion;
        if (!speaking) {
            target = 0;
        }
    }

    /** Whether the speaker is talking. */
    public boolean speaking() {
        return speaking;
    }

    /** How loud the voice is now, 0 to 1. */
    public float level() {
        return level;
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        if (!motion) {
            return;
        }
        syllable -= delta;
        if (syllable <= 0) {
            syllable = MathUtils.lerp(SYLLABLE_MIN, SYLLABLE_MAX, random.nextFloat());
            target = !speaking ? 0 : random.nextFloat() < PAUSE_CHANCE ? .05f : .35f + .65f * random.nextFloat();
            for (int i = 0; i < BARS; i++) {
                float middle = 1 - Math.abs(i - (BARS - 1) / 2f) / (BARS / 2f);
                targets[i] = (.25f + .75f * middle) * (.35f + .65f * random.nextFloat());
            }
        }
        float rate = target > level ? RISE : FALL;
        level += (target - level) * Math.min(1, delta * rate);
        for (int i = 0; i < BARS; i++) {
            heights[i] += (targets[i] * level - heights[i]) * Math.min(1, delta * RISE);
        }
        if (feed != null) {
            feed.voice(level);
        }
    }

    @Override
    public float getPrefWidth() {
        return prefWidth;
    }

    @Override
    public float getPrefHeight() {
        return prefHeight;
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        float alpha = getColor().a * parentAlpha;
        float x = getX();
        float width = getWidth();
        float middle = getY() + getHeight() / 2;
        float bar = Math.max(1, (width - GAP * (BARS - 1)) / BARS);
        Color before = new Color(batch.getColor());
        batch.setColor(color.r, color.g, color.b, alpha * .25f);
        batch.draw(white, x, middle - .5f, width, 1);
        for (int i = 0; i < BARS; i++) {
            float half = Math.max(.5f, heights[i] * getHeight() / 2);
            batch.setColor(color.r, color.g, color.b, alpha * (.45f + .55f * Math.min(1, heights[i] * 1.5f)));
            batch.draw(white, x + i * (bar + GAP), middle - half, bar, 2 * half);
        }
        batch.setColor(before);
    }
}
