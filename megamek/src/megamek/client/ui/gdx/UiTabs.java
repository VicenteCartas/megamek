/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import megamek.client.ui.design.DesignTokens;

/**
 * Design-system tabs (docs/design-system/tokens.md, Tabs): labels on the window surface that switch between the pages
 * of one view. The selected tab has an underline in the selection color whether or not the strip has focus, and a hairline runs under
 * the whole strip; the tabs sit at its left. Compact tabs are for panels, regular ones for a window's top-level pages.
 */
public class UiTabs extends UiOptionBar {
    private final Drawable over;
    private final float underline = DesignTokens.shipped().size().get("selection.width");
    private final float ring = DesignTokens.shipped().size().get("focus.width");

    UiTabs(UiKit kit, boolean compact, String... labels) {
        super(kit, compact, labels);
        over = kit.skin.getDrawable("tab-over");
        left();
        for (Option option : options()) {
            add(option).height(barHeight());
        }
    }

    @Override
    protected void drawBackground(Batch batch, float parentAlpha, float x, float y) {
        Color tint = getColor();
        batch.setColor(tint.r, tint.g, tint.b, tint.a * parentAlpha);
        for (Option option : options()) {
            if (!isDisabled() && !option.isDisabled() && (option.isOver() || option.isPressed())) {
                over.draw(batch, x + option.getX(), y + option.getY(), option.getWidth(), option.getHeight());
            }
        }
        line(batch, colors.hairline, parentAlpha, x, y, getWidth(), border);
        Option selected = option(getSelectedIndex());
        float left = x + selected.getX();
        float bottom = y + selected.getY();
        float width = selected.getWidth();
        float height = selected.getHeight();
        line(batch, isDisabled() ? colors.disabledUnderline : colors.underline, parentAlpha, left, bottom, width,
              underline);
        for (int i = 0; i < count(); i++) {
            if (ringed(i)) {
                Option option = option(i);
                float ox = x + option.getX();
                float oy = y + option.getY();
                float ow = option.getWidth();
                float oh = option.getHeight();
                line(batch, colors.ring, parentAlpha, ox, oy, ow, ring);
                line(batch, colors.ring, parentAlpha, ox, oy + oh - ring, ow, ring);
                line(batch, colors.ring, parentAlpha, ox, oy + ring, ring, oh - 2 * ring);
                line(batch, colors.ring, parentAlpha, ox + ow - ring, oy + ring, ring, oh - 2 * ring);
            }
        }
    }
}
