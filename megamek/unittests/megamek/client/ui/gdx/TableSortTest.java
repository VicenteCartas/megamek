/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import megamek.client.ui.gdx.TableSort.Direction;
import org.junit.jupiter.api.Test;

class TableSortTest {

    private enum Key implements TableSort.Key {
        NAME(Direction.ASCENDING), VALUE(Direction.DESCENDING);

        private final Direction start;

        Key(Direction start) {
            this.start = start;
        }

        @Override
        public Direction defaultDirection() {
            return start;
        }
    }

    private record Item(String name, Integer value) { }

    private static final Comparator<Item> BY_NAME = Comparator.comparing(Item::name);

    private static List<String> sorted(List<Item> items, Direction direction) {
        List<Item> copy = new ArrayList<>(items);
        copy.sort(TableSort.comparator(Item::value, direction, BY_NAME));
        return copy.stream().map(Item::name).toList();
    }

    @Test
    void aKeyStartsInItsDefaultDirection() {
        assertEquals(new TableSort<>(Key.NAME, Direction.ASCENDING), TableSort.of(Key.NAME));
        assertEquals(new TableSort<>(Key.VALUE, Direction.DESCENDING), TableSort.of(Key.VALUE));
        assertTrue(TableSort.of(Key.NAME).ascending());
        assertFalse(TableSort.of(Key.VALUE).ascending());
    }

    @Test
    void aClickOnTheKeyInUseFlipsItAndAnotherKeyStartsInItsDefault() {
        TableSort<Key> byName = TableSort.of(Key.NAME);
        assertEquals(new TableSort<>(Key.NAME, Direction.DESCENDING), byName.click(Key.NAME));
        assertEquals(byName, byName.click(Key.NAME).click(Key.NAME));
        assertEquals(new TableSort<>(Key.VALUE, Direction.DESCENDING), byName.click(Key.VALUE));
        TableSort<Key> nameDown = new TableSort<>(Key.NAME, Direction.DESCENDING);
        assertEquals(new TableSort<>(Key.VALUE, Direction.DESCENDING), nameDown.click(Key.VALUE),
              "another key ignores the direction in use");
        assertEquals(new TableSort<>(Key.NAME, Direction.ASCENDING), TableSort.of(Key.VALUE).click(Key.NAME));
    }

    @Test
    void flippingAndChangingTheKeyKeepTheRest() {
        assertEquals(Direction.ASCENDING, Direction.DESCENDING.flipped());
        assertEquals(Direction.DESCENDING, Direction.ASCENDING.flipped());
        TableSort<Key> byValue = TableSort.of(Key.VALUE);
        assertEquals(new TableSort<>(Key.VALUE, Direction.ASCENDING), byValue.flipped());
        assertEquals(new TableSort<>(Key.NAME, Direction.DESCENDING), byValue.withKey(Key.NAME));
    }

    @Test
    void aSortNeedsAKeyAndADirection() {
        assertThrows(NullPointerException.class, () -> new TableSort<>(null, Direction.ASCENDING));
        assertThrows(NullPointerException.class, () -> new TableSort<>(Key.NAME, null));
    }

    @Test
    void theComparatorRunsEitherWay() {
        List<Item> items = List.of(new Item("b", 2), new Item("c", 3), new Item("a", 1));
        assertEquals(List.of("a", "b", "c"), sorted(items, Direction.ASCENDING));
        assertEquals(List.of("c", "b", "a"), sorted(items, Direction.DESCENDING));
    }

    @Test
    void missingValuesGoLastInEitherDirection() {
        List<Item> items = List.of(new Item("none", null), new Item("low", 1), new Item("also none", null),
              new Item("high", 9));
        assertEquals(List.of("low", "high", "also none", "none"), sorted(items, Direction.ASCENDING));
        assertEquals(List.of("high", "low", "also none", "none"), sorted(items, Direction.DESCENDING));
    }

    @Test
    void tiesFollowTheTiebreakWhicheverTheDirection() {
        List<Item> items = List.of(new Item("d", 5), new Item("b", 5), new Item("c", 1), new Item("a", 5));
        assertEquals(List.of("c", "a", "b", "d"), sorted(items, Direction.ASCENDING));
        assertEquals(List.of("a", "b", "d", "c"), sorted(items, Direction.DESCENDING));
    }

    @Test
    void aComparatorOfItsOwnComparesTheValues() {
        List<Item> items = new ArrayList<>(List.of(new Item("B", 0), new Item("a", 0), new Item("C", 0)));
        items.sort(TableSort.comparator(Item::name, String.CASE_INSENSITIVE_ORDER, Direction.DESCENDING,
              (first, second) -> 0));
        assertEquals(List.of("C", "B", "a"), items.stream().map(Item::name).toList());
    }

    @Test
    void extremeComparisonsAreNotNegatedIntoTheWrongOrder() {
        Comparator<Integer> extreme = (a, b) -> a.equals(b) ? 0 : (a < b) ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        List<Item> items = new ArrayList<>(List.of(new Item("low", 1), new Item("high", 2)));
        items.sort(TableSort.comparator(Item::value, extreme, Direction.DESCENDING, BY_NAME));
        assertEquals(List.of("high", "low"), items.stream().map(Item::name).toList());
    }
}
