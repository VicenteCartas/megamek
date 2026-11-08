/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.List;
import java.util.function.IntFunction;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup;
import com.badlogic.gdx.scenes.scene2d.utils.Layout;
import megamek.client.ui.design.DesignTokens;

/**
 * A row of status chips ({@link UiKit#chip(String, UiKit.Tone, boolean)}) that keeps each chip whole: the chips that do not
 * fit the row's width fold into a muted "+n" after the last that does, rather than squeezing every chip until its icon
 * and words spill over its edge. The chips stand {@code xs} apart. Made by {@link UiKit#chipRow(List, int, IntFunction)}.
 */
public final class UiChipRow extends WidgetGroup {
    private final List<Actor> chips;
    private final int folded;
    private final IntFunction<String> more;
    private final Label moreLabel;
    /** Measures a "+n" without touching the one that shows. */
    private final Label measure;
    private final float gap;

    UiChipRow(UiKit kit, List<Actor> chips, int folded, IntFunction<String> more) {
        this.chips = List.copyOf(chips);
        this.folded = folded;
        this.more = more;
        gap = DesignTokens.shipped().space().get("xs");
        moreLabel = kit.typeLabel("", "small", "text.muted");
        moreLabel.setName("chip-row-more");
        measure = kit.typeLabel("", "small", "text.muted");
        this.chips.forEach(this::addActor);
        addActor(moreLabel);
    }

    /** How many chips show at the row's width; the rest are folded into "+n". */
    public int shownChips() {
        int shown = 0;
        for (Actor chip : chips) {
            if (chip.isVisible()) {
                shown++;
            }
        }
        return shown;
    }

    /** The "+n" after the chips, empty while every chip shows and none was folded before. */
    public Label more() {
        return moreLabel;
    }

    @Override
    public void layout() {
        float width = getWidth();
        int fit = chips.size();
        while (fit > 0 && widthOf(fit, folded + chips.size() - fit) > width) {
            fit--;
        }
        int hidden = folded + chips.size() - fit;
        moreLabel.setText(hidden > 0 ? more.apply(hidden) : "");
        moreLabel.setVisible(hidden > 0);
        float x = 0;
        float height = getHeight();
        for (int index = 0; index < chips.size(); index++) {
            Actor chip = chips.get(index);
            boolean shown = index < fit;
            chip.setVisible(shown);
            if (shown) {
                float chipWidth = prefWidth(chip);
                float chipHeight = prefHeight(chip);
                chip.setBounds(x, Math.round((height - chipHeight) / 2), chipWidth, chipHeight);
                x += chipWidth + gap;
            }
        }
        if (hidden > 0) {
            float labelHeight = moreLabel.getPrefHeight();
            moreLabel.setBounds(x, Math.round((height - labelHeight) / 2), moreLabel.getPrefWidth(), labelHeight);
        }
    }

    /** The width the first {@code count} chips take, and the "+n" for {@code hidden} more. */
    private float widthOf(int count, int hidden) {
        float width = 0;
        for (int index = 0; index < count; index++) {
            width += prefWidth(chips.get(index)) + gap;
        }
        if (hidden > 0) {
            measure.setText(more.apply(hidden));
            width += measure.getPrefWidth();
        } else if (count > 0) {
            width -= gap;
        }
        return width;
    }

    @Override
    public float getPrefWidth() {
        return widthOf(chips.size(), folded);
    }

    @Override
    public float getMinWidth() {
        return 0;
    }

    @Override
    public float getPrefHeight() {
        float height = moreLabel.getPrefHeight();
        for (Actor chip : chips) {
            height = Math.max(height, prefHeight(chip));
        }
        return height;
    }

    private static float prefWidth(Actor actor) {
        return actor instanceof Layout layout ? layout.getPrefWidth() : actor.getWidth();
    }

    private static float prefHeight(Actor actor) {
        return actor instanceof Layout layout ? layout.getPrefHeight() : actor.getHeight();
    }
}
