/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.Comparator;
import java.util.List;

import megamek.client.ui.TableSort;
import megamek.common.loaders.MekSummary;

/**
 * What the Requisition view's results sort by: the name, which they start in, the value they show (Battle or Point),
 * the tonnage, the year of introduction, the movement (walk, then run, then jump), the armor, the role, the rules level
 * or the type. Numbers start high first and text from A to Z ({@link TableSort#click}); a unit without armor, a role or
 * a rules level sorts after those with one, whichever the direction, and units that sort alike stay in name order.
 */
public enum RequisitionSort implements TableSort.Key {
    NAME("Lobby.requisition.sort.name", false),
    VALUE("Lobby.requisition.bv", true),
    TONS("Lobby.requisition.sort.tons", true),
    YEAR("Lobby.requisition.sort.year", true),
    MOVE("Lobby.requisition.column.move", true),
    ARMOR("Lobby.requisition.column.armor", true),
    ROLE("Lobby.requisition.column.role", false),
    LEVEL("Lobby.requisition.column.level", false),
    TYPE("Lobby.requisition.sort.type", false);

    /** The results' order until the Commander picks another: by name, A to Z. */
    public static final TableSort<RequisitionSort> BY_NAME = TableSort.of(NAME);

    /**
     * No tiebreak: the rows come in name order and the sort is stable, so rows that sort alike keep that order without
     * comparing their names again.
     */
    private static final Comparator<RequisitionRow> KEEP_ORDER = (first, second) -> 0;
    /** Walk, run and jump points packed into one number that sorts as the three in turn. */
    private static final long MOVE_PLACE = 10_000;

    private final String messageKey;
    private final boolean numeric;

    RequisitionSort(String messageKey, boolean numeric) {
        this.messageKey = messageKey;
        this.numeric = numeric;
    }

    /** The message key of the key's name; the value's is the Battle Value's. */
    public String messageKey() {
        return messageKey;
    }

    /** The message key of the key's name, the value named as the one shown: the Point Value with {@code pv}. */
    public String messageKey(boolean pv) {
        return ((this == VALUE) && pv) ? "Lobby.requisition.pv" : messageKey;
    }

    @Override
    public TableSort.Direction defaultDirection() {
        return numeric ? TableSort.Direction.DESCENDING : TableSort.Direction.ASCENDING;
    }

    /** The key of the results' {@code column}. */
    public static RequisitionSort of(RequisitionRow.Column column) {
        return switch (column) {
            case MOVE -> MOVE;
            case ARMOR -> ARMOR;
            case ROLE -> ROLE;
            case LEVEL -> LEVEL;
        };
    }

    /**
     * {@code rows}, which come in the catalogue's name order, in {@code sort}'s order, the value being the Point Value
     * with {@code pv}, else the Battle Value; rows in name order come back as they are when that is the order.
     */
    public static List<RequisitionRow> sorted(List<RequisitionRow> rows, TableSort<RequisitionSort> sort, boolean pv) {
        if (sort.equals(BY_NAME)) {
            return rows;
        }
        return rows.stream().sorted(order(sort, pv)).toList();
    }

    /** The order of rows in name order in {@code sort}; it leaves ties to the stable sort. */
    private static Comparator<RequisitionRow> order(TableSort<RequisitionSort> sort, boolean pv) {
        TableSort.Direction direction = sort.direction();
        return switch (sort.key()) {
            case NAME -> TableSort.comparator(RequisitionRow::sortName, direction, KEEP_ORDER);
            case VALUE -> pv ? TableSort.comparator(RequisitionRow::pv, direction, KEEP_ORDER)
                  : TableSort.comparator(RequisitionRow::bv, direction, KEEP_ORDER);
            case TONS -> TableSort.comparator(RequisitionRow::tons, direction, KEEP_ORDER);
            case YEAR -> TableSort.comparator(row -> row.unit().getYear(), direction, KEEP_ORDER);
            case MOVE -> TableSort.comparator(RequisitionSort::move, direction, KEEP_ORDER);
            case ARMOR -> TableSort.comparator(RequisitionSort::armor, direction, KEEP_ORDER);
            case ROLE -> TableSort.comparator(row -> blankAsNone(row.role()), String.CASE_INSENSITIVE_ORDER,
                  direction, KEEP_ORDER);
            case LEVEL -> TableSort.comparator(RequisitionSort::level, direction, KEEP_ORDER);
            case TYPE -> TableSort.comparator(RequisitionRow::type, String.CASE_INSENSITIVE_ORDER, direction,
                  KEEP_ORDER);
        };
    }

    private static long move(RequisitionRow row) {
        MekSummary unit = row.unit();
        return (Math.max(0, unit.getWalkMp()) * MOVE_PLACE + Math.max(0, unit.getRunMp())) * MOVE_PLACE
              + Math.max(0, unit.getJumpMp());
    }

    private static Integer armor(RequisitionRow row) {
        int armor = row.unit().getTotalArmor();
        return (armor > 0) ? armor : null;
    }

    private static Integer level(RequisitionRow row) {
        return (row.levelRank() == RequisitionRow.NO_LEVEL) ? null : row.levelRank();
    }

    private static String blankAsNone(String text) {
        return ((text == null) || text.isBlank()) ? null : text;
    }
}
