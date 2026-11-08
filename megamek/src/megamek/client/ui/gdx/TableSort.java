/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.Comparator;
import java.util.Objects;
import java.util.function.Function;

/**
 * The order of a sortable list: the key it sorts by and the direction. It is plain data with no toolkit, so the layer
 * that fills the list sorts with it on any thread, and the controls that set it ({@link UiSortControl},
 * {@link UiSortHeader}) only show it. A click on a key ({@link #click}) flips the direction of the key in use and
 * starts another key in its own default direction: numbers high first, text from A to Z.
 *
 * @param key       what the list sorts by
 * @param direction which way
 * @param <K>       the keys a list sorts by
 */
public record TableSort<K extends TableSort.Key>(K key, Direction direction) {

    /** Which way a list runs. */
    public enum Direction {
        /** Low to high, A to Z. */
        ASCENDING,
        /** High to low, Z to A. */
        DESCENDING;

        /** The other way. */
        public Direction flipped() {
            return (this == ASCENDING) ? DESCENDING : ASCENDING;
        }
    }

    /** A key a list sorts by, which knows the direction it starts in. */
    public interface Key {
        /** The direction this key starts in: {@link Direction#DESCENDING} for numbers, ascending for text. */
        Direction defaultDirection();
    }

    public TableSort {
        Objects.requireNonNull(key);
        Objects.requireNonNull(direction);
    }

    /** Sorting by {@code key} in its default direction. */
    public static <K extends Key> TableSort<K> of(K key) {
        return new TableSort<>(key, key.defaultDirection());
    }

    /** The sort after a click on {@code clicked}: the key in use flips, another key starts in its default direction. */
    public TableSort<K> click(K clicked) {
        return clicked.equals(key) ? flipped() : of(clicked);
    }

    /** The same key the other way. */
    public TableSort<K> flipped() {
        return new TableSort<>(key, direction.flipped());
    }

    /** {@code next} in this sort's direction. */
    public TableSort<K> withKey(K next) {
        return new TableSort<>(next, direction);
    }

    public boolean ascending() {
        return direction == Direction.ASCENDING;
    }

    /**
     * An order by {@code value} in {@code direction}: a row whose value is null has none and goes last whichever the
     * direction; rows with equal values, or none, follow {@code tiebreak}, which the direction does not turn.
     */
    public static <T, V extends Comparable<? super V>> Comparator<T> comparator(Function<? super T, ? extends V> value,
          Direction direction, Comparator<? super T> tiebreak) {
        return comparator(value, Comparator.naturalOrder(), direction, tiebreak);
    }

    /** An order as {@link #comparator(Function, Direction, Comparator)}, the values compared by {@code order}. */
    public static <T, V> Comparator<T> comparator(Function<? super T, ? extends V> value, Comparator<? super V> order,
          Direction direction, Comparator<? super T> tiebreak) {
        Objects.requireNonNull(value);
        Objects.requireNonNull(order);
        Objects.requireNonNull(direction);
        Objects.requireNonNull(tiebreak);
        return (first, second) -> {
            V a = value.apply(first);
            V b = value.apply(second);
            if ((a == null) != (b == null)) {
                return (a == null) ? 1 : -1;
            }
            if (a != null) {
                int compared = (direction == Direction.ASCENDING) ? order.compare(a, b) : order.compare(b, a);
                if (compared != 0) {
                    return compared;
                }
            }
            return tiebreak.compare(first, second);
        };
    }
}
