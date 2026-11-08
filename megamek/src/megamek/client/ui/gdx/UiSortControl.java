/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextTooltip;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.Pools;
import megamek.client.ui.design.DesignTokens;

/**
 * The order of a list, set from above it: a caption ("Sort"), an inline {@link UiSelect} of the keys and a compact icon
 * button with an up or down arrow for the direction, its tooltip naming it. Choosing another key starts it in its own
 * default direction and the button flips the direction ({@link TableSort}); either fires one ChangeEvent from the
 * control, whose {@link #sort()} is then the new order. A view's own change ({@link #sort(TableSort)},
 * {@link #keys}) fires none, so the control and sortable column headers ({@link UiSortHeader}) can show one order.
 *
 * @param <K> the keys the list sorts by
 */
public final class UiSortControl<K extends TableSort.Key> extends Table {
    private final UiKit kit;
    private final Function<K, String> names;
    private final String ascendingText;
    private final String descendingText;
    private final Cell<Actor> selectCell;
    private final UiButton direction;
    private final TextTooltip tip;
    private List<K> keys;
    private UiSelect select;
    private TableSort<K> sort;

    /**
     * @param caption    the caption before the keys, such as "Sort"
     * @param ascending  the direction button's tooltip while the order is ascending
     * @param descending its tooltip while the order is descending
     * @param keys       the keys it offers, in their order in the list
     * @param names      each key's name
     * @param initial    the order it shows first; its key is one of {@code keys}
     */
    UiSortControl(UiKit kit, String caption, String ascending, String descending, List<K> keys,
          Function<K, String> names, TableSort<K> initial) {
        this.kit = kit;
        this.names = names;
        ascendingText = ascending;
        descendingText = descending;
        DesignTokens tokens = DesignTokens.shipped();
        Label label = kit.typeLabel(caption, "small", "text.secondary");
        add(label).padRight(tokens.space().get("xs"));
        selectCell = add().minWidth(0);
        direction = kit.button("button-icon" + UiTheme.COMPACT, "arrow-up", null, null);
        direction.setName("sort-direction");
        tip = kit.tip(direction, true);
        UiKit.onChange(direction, () -> {
            sort = sort.flipped();
            showDirection();
            fireChange();
        });
        stopOwnChange(direction);
        add(direction).padLeft(tokens.space().get("xxs"));
        keys(keys, initial);
    }

    /** The order it shows. */
    public TableSort<K> sort() {
        return sort;
    }

    /** Shows {@code next} without an event; its key must be one it offers. */
    public UiSortControl<K> sort(TableSort<K> next) {
        int index = keys.indexOf(next.key());
        if (index < 0) {
            throw new IllegalArgumentException("Not a key of this control: " + next.key());
        }
        sort = next;
        select.select(index);
        showDirection();
        return this;
    }

    /** Offers {@code next} instead, showing {@code shown} (whose key is one of them), without an event. */
    public UiSortControl<K> keys(List<K> next, TableSort<K> shown) {
        keys = List.copyOf(next);
        String[] items = new String[keys.size()];
        for (int i = 0; i < items.length; i++) {
            items[i] = names.apply(keys.get(i));
        }
        UiSelect replacement = kit.tokenSelectInline(items);
        replacement.setName("sort-key");
        replacement.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (actor != replacement) {
                    return;
                }
                event.stop();
                sort = sort.click(keys.get(replacement.getSelectedIndex()));
                showDirection();
                fireChange();
            }
        });
        select = replacement;
        selectCell.setActor(replacement);
        sort(shown);
        invalidateHierarchy();
        return this;
    }

    /** The keys it offers. */
    public List<K> keys() {
        return keys;
    }

    /** Runs {@code action} with the new order each time the user changes it. */
    public UiSortControl<K> onSort(Consumer<TableSort<K>> action) {
        UiKit.onChange(this, () -> action.accept(sort));
        return this;
    }

    /** The keys' select, for tests and a view's focus. */
    public UiSelect select() {
        return select;
    }

    /** The direction button, for tests. */
    public UiButton directionButton() {
        return direction;
    }

    /** The direction button's tooltip text. */
    public String directionTip() {
        return tip.getActor().getText().toString();
    }

    private void showDirection() {
        boolean up = sort.ascending();
        ((Image) direction.icons.get(0)).setDrawable(kit.skin.getDrawable(up ? "icon-arrow-up" : "icon-arrow-down"));
        tip.getActor().setText(up ? ascendingText : descendingText);
    }

    private void fireChange() {
        ChangeListener.ChangeEvent event = Pools.obtain(ChangeListener.ChangeEvent.class);
        try {
            fire(event);
        } finally {
            Pools.free(event);
        }
    }

    /** Keeps a part's own ChangeEvent from reaching the control's listeners, which hear the control's alone. */
    private static void stopOwnChange(Actor part) {
        part.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (actor == part) {
                    event.stop();
                }
            }
        });
    }
}
