/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Pools;
import megamek.client.ui.design.DesignTokens;

/**
 * A sortable column's title over a list: the overline name in text.muted, text.secondary under the pointer, and, while
 * its column is the key in use, text.primary with an accent arrow, up for ascending and down for descending. The arrow
 * keeps its room when hidden, so the header is as wide whether or not it is the key in use and the columns beneath it
 * stand still. A click fires a ChangeEvent; the view decides what the click does ({@link TableSort#click}) and shows
 * the result with {@link #direction}, which fires nothing.
 */
public final class UiSortHeader extends Table {
    private final UiKit kit;
    private final Label label;
    private final UiKit.Icon arrow;
    private final ClickListener clicks;
    private final Color muted = UiTheme.tokenColor("text.muted");
    private final Color over = UiTheme.tokenColor("text.secondary");
    private final Color active = UiTheme.tokenColor("text.primary");
    private final Color accent = UiTheme.tokenColor("accent.primary");
    private TableSort.Direction direction;

    /**
     * @param text  the column's name
     * @param align {@link Align#left}: the name leads and the arrow follows it; {@link Align#right}, for a column of
     *              numbers set right: the arrow leads, so the name ends where the numbers do
     */
    UiSortHeader(UiKit kit, String text, int align) {
        this.kit = kit;
        DesignTokens tokens = DesignTokens.shipped();
        float iconSize = tokens.size().get("icon.sm");
        float gap = tokens.space().get("xxs");
        label = kit.roleTypeLabel("overline", text, "text.muted");
        label.setEllipsis(true);
        arrow = kit.icon("arrow-up", iconSize, accent);
        arrow.setVisible(false);
        if (Align.isRight(align)) {
            right();
            add(arrow).size(iconSize).padRight(gap);
            add(label).right().minWidth(0);
        } else {
            left();
            add(label).left().minWidth(0);
            add(arrow).size(iconSize).padLeft(gap);
        }
        label.setTouchable(Touchable.disabled);
        arrow.setTouchable(Touchable.disabled);
        setTouchable(Touchable.enabled);
        clicks = new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                ChangeListener.ChangeEvent change = Pools.obtain(ChangeListener.ChangeEvent.class);
                try {
                    fire(change);
                } finally {
                    Pools.free(change);
                }
            }
        };
        addListener(clicks);
    }

    /** Shows {@code text} as the column's name, cased as an overline. */
    public UiSortHeader setText(String text) {
        label.setText(kit.cased("overline", text));
        invalidateHierarchy();
        return this;
    }

    /** The name it shows. */
    public String text() {
        return label.getText().toString();
    }

    /** Shows the arrow of {@code shown} when its column is the key in use, or no arrow when null; fires nothing. */
    public UiSortHeader direction(TableSort.Direction shown) {
        direction = shown;
        arrow.setVisible(shown != null);
        if (shown != null) {
            arrow.setDrawable(kit.skin.getDrawable(shown == TableSort.Direction.ASCENDING ? "icon-arrow-up"
                  : "icon-arrow-down"));
        }
        return this;
    }

    /** The direction its arrow shows, or null when its column is not the key in use. */
    public TableSort.Direction direction() {
        return direction;
    }

    /** Whether the pointer is over it. */
    public boolean isOver() {
        return clicks.isOver();
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        label.getStyle().fontColor = (direction != null) ? active : clicks.isOver() ? over : muted;
        super.draw(batch, parentAlpha);
    }
}
