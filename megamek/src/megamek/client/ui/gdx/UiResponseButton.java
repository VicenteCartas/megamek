/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Label;

/**
 * A response on a comm channel: a framed row with its hotkey in a box at the left and its content beside it, glowing in
 * the signal color while hovered or focused, the glow breathing while focused. When chosen it sweeps full of light
 * before its view acts ({@link #confirm()}), as the answer goes out. Its view sets focus; a click fires a ChangeEvent.
 * Without motion the glow and sweep jump to their ends.
 */
public class UiResponseButton extends Button {
    private static final float GLOW_SECONDS = .18f;
    private static final float SWEEP_SECONDS = .32f;

    private final TextureRegion white;
    private final Color glowColor;
    private final Label key;
    private final boolean motion;
    private boolean focused;
    private float glow;
    private float time;
    private float sweep = -1;

    /**
     * A response showing {@code content}, with hotkey {@code key} (null for none), glowing {@code glow}; {@code white}
     * is a solid white region for its frame. {@code pad} is the space inside the frame.
     */
    public UiResponseButton(Actor content, Label key, TextureRegion white, Color glow, float pad, boolean motion) {
        super(new ButtonStyle());
        this.white = white;
        this.glowColor = new Color(glow);
        this.key = key;
        this.motion = motion;
        pad(pad, pad * 1.4f, pad, pad * 1.4f);
        if (key != null) {
            add(key).top().minWidth(key.getPrefHeight() * 1.5f).padRight(pad * 1.4f);
        }
        add(content).growX().minWidth(0);
    }

    /** Shows the button focused, as the response Enter would take, without an event. */
    public UiResponseButton focused(boolean value) {
        focused = value;
        return this;
    }

    /** Whether the button shows focused. */
    public boolean focused() {
        return focused;
    }

    /** Starts the sweep of light that shows this response going out. */
    public void confirm() {
        sweep = motion ? 0 : 1;
    }

    /** Whether the sweep started by {@link #confirm()} has finished; false before it starts. */
    public boolean confirmed() {
        return sweep >= 1;
    }

    /** How long the sweep takes, in seconds; 0 without motion. */
    public float confirmSeconds() {
        return motion ? SWEEP_SECONDS : 0;
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        time += delta;
        float target = !isDisabled() && (focused || isOver()) ? 1 : 0;
        glow = motion ? MathUtils.clamp(glow + Math.signum(target - glow) * delta / GLOW_SECONDS, 0, 1) : target;
        if (sweep >= 0 && sweep < 1) {
            sweep = Math.min(1, sweep + delta / SWEEP_SECONDS);
        }
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        validate();
        float alpha = getColor().a * parentAlpha * (isDisabled() ? .45f : 1);
        float x = getX();
        float y = getY();
        float w = getWidth();
        float h = getHeight();
        Color before = new Color(batch.getColor());
        float lit = glow * (focused && motion ? .8f + .2f * MathUtils.sin(time * 3.2f) : 1);
        Color c = glowColor;
        // Halo: rings of fading light outside the frame
        for (int ring = 1; ring <= 4; ring++) {
            float out = ring * 2f;
            batch.setColor(c.r, c.g, c.b, alpha * lit * .09f * (5 - ring) / 4f);
            edges(batch, x - out, y - out, w + 2 * out, h + 2 * out, 2);
        }
        batch.setColor(.02f, .05f, .06f, alpha * .55f);
        batch.draw(white, x, y, w, h);
        batch.setColor(c.r, c.g, c.b, alpha * (.06f + .12f * lit + (isPressed() ? .1f : 0)));
        batch.draw(white, x, y, w, h);
        if (sweep >= 0) {
            float eased = 1 - (1 - sweep) * (1 - sweep);
            batch.setColor(c.r, c.g, c.b, alpha * (.45f - .2f * sweep));
            batch.draw(white, x, y, w * eased, h);
        }
        batch.setColor(c.r, c.g, c.b, alpha * (.3f + .6f * lit));
        edges(batch, x, y, w, h, 1);
        batch.setColor(c.r, c.g, c.b, alpha * (.55f + .45f * lit));
        batch.draw(white, x, y, 3, h);
        if (key != null && key.hasParent()) {
            batch.setColor(c.r, c.g, c.b, alpha * (.35f + .5f * lit));
            edges(batch, x + key.getX() - 3, y + key.getY() - 2, key.getWidth() + 6, key.getHeight() + 4, 1);
        }
        batch.setColor(before);
        super.draw(batch, parentAlpha * (isDisabled() ? .55f : 1));
    }

    private void edges(Batch batch, float x, float y, float w, float h, float thickness) {
        batch.draw(white, x, y, w, thickness);
        batch.draw(white, x, y + h - thickness, w, thickness);
        batch.draw(white, x, y + thickness, thickness, h - 2 * thickness);
        batch.draw(white, x + w - thickness, y + thickness, thickness, h - 2 * thickness);
    }
}
