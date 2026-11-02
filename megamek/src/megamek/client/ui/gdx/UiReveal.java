/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.design.DesignTokens;

/**
 * A column of bands that show one after another (tokens.md, Motion): each fades in and rises {@code space.md} into
 * place over {@code motion.medium} on the token easing, a {@code motion.fast} step after the one before, as a details
 * panel unfolds. The reveal runs once, from the first frame the column is on stage; bands past {@link #STAGGER_CAP}
 * start with the last staggered one so a long column never keeps its end waiting. Without motion every band shows at
 * once.
 */
public class UiReveal extends Table {
    /** The band index past which bands no longer wait longer than the one before. */
    public static final int STAGGER_CAP = 8;

    private final List<Band> bands = new ArrayList<>();
    private final float step;
    private final float duration;
    private final float rise;
    private final float[] easing;
    private float elapsed;
    private boolean done;

    /** A column that reveals its bands, or shows them at once when {@code motion} is off (reduce motion). */
    public UiReveal(boolean motion) {
        DesignTokens tokens = DesignTokens.shipped();
        step = tokens.duration("fast") / 1000f;
        duration = tokens.duration("medium") / 1000f;
        rise = tokens.space().get("md");
        easing = tokens.easing();
        done = !motion;
        top().left();
    }

    /** A column as {@link #UiReveal(boolean)} whose first band waits {@code delay} seconds, as for a card to unfold. */
    public UiReveal(boolean motion, float delay) {
        this(motion);
        elapsed = -delay;
    }

    /** Adds {@code block} as the next band, on a row of its own across the column. */
    public Cell<Band> band(Actor block) {
        Band band = new Band(block, bands.size());
        bands.add(band);
        Cell<Band> cell = add(band).growX().minWidth(0);
        row();
        return cell;
    }

    /** Whether every band has shown in full. */
    public boolean done() {
        return done;
    }

    /** Shows every band in full at once. */
    public void finish() {
        done = true;
    }

    /** How far band {@code index} has shown, 0 to 1 on the token easing. */
    public float shown(int index) {
        if (done) {
            return 1;
        }
        return ease(Math.max(0, Math.min(1, (elapsed - delay(index, step)) / duration)), easing);
    }

    /** How long band {@code index} of this column waits before it starts to show, 0 without motion. */
    public float delay(int index) {
        return done ? 0 : delay(index, step);
    }

    /** How long band {@code index} waits, at {@code step} after the one before, up to {@link #STAGGER_CAP}. */
    public static float delay(int index, float step) {
        return Math.min(index, STAGGER_CAP) * step;
    }

    /**
     * {@code t} on the cubic Bézier easing {@code curve} (x1, y1, x2, y2, from (0, 0) to (1, 1)), as CSS's
     * cubic-bezier: x solved for t by bisection, then y at that point.
     */
    public static float ease(float t, float[] curve) {
        if (t <= 0) {
            return 0;
        }
        if (t >= 1) {
            return 1;
        }
        float low = 0;
        float high = 1;
        float s = t;
        for (int i = 0; i < 24; i++) {
            s = (low + high) / 2;
            if (bezier(s, curve[0], curve[2]) < t) {
                low = s;
            } else {
                high = s;
            }
        }
        return bezier(s, curve[1], curve[3]);
    }

    private static float bezier(float s, float p1, float p2) {
        float inverse = 1 - s;
        return 3 * inverse * inverse * s * p1 + 3 * inverse * s * s * p2 + s * s * s;
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        if (done) {
            return;
        }
        elapsed += delta;
        done = elapsed >= delay(bands.size() - 1, step) + duration;
        if (Gdx.graphics != null) {
            Gdx.graphics.requestRendering();
        }
    }

    /** One band of the column: drawn faded and lowered until it has shown. */
    public final class Band extends Container<Actor> {
        private final int index;

        private Band(Actor block, int index) {
            super(block);
            this.index = index;
            fillX();
            top().left();
        }

        @Override
        public void draw(Batch batch, float parentAlpha) {
            float shown = shown(index);
            if (shown <= 0) {
                return;
            }
            if (shown >= 1) {
                super.draw(batch, parentAlpha);
                return;
            }
            float y = getY();
            setY(y - (1 - shown) * rise);
            super.draw(batch, parentAlpha * shown);
            setY(y);
        }
    }
}
