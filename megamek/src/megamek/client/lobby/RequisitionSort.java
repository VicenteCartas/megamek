/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.Comparator;
import java.util.List;

import megamek.client.ui.gdx.TableSort;
import megamek.common.TechConstants;
import megamek.common.loaders.MekSummary;

/**
 * What the Requisition view's results sort by: the name, which they start in, the Battle or Point Value, the tonnage,
 * the year of introduction, the movement (walk, then run, then jump), the armor, the role, the rules level or the type.
 * Numbers start high first and text from A to Z ({@link TableSort#click}); a unit without armor or a role sorts after
 * those with one, whichever the direction, and units that sort alike stay in name order.
 */
public enum RequisitionSort implements TableSort.Key {
    NAME("Lobby.requisition.sort.name", false),
    BV("Lobby.requisition.bv", true),
    PV("Lobby.requisition.pv", true),
    TONS("Lobby.requisition.sort.tons", true),
    YEAR("Lobby.requisition.sort.year", true),
    MOVE("Lobby.requisition.column.move", true),
    ARMOR("Lobby.requisition.column.armor", true),
    ROLE("Lobby.requisition.column.role", false),
    LEVEL("Lobby.requisition.column.level", false),
    TYPE("Lobby.requisition.sort.type", false);

    /** The results' order until the Commander picks another: by name, A to Z. */
    public static final TableSort<RequisitionSort> BY_NAME = TableSort.of(NAME);

    /** The catalogue's order: by name without case or leading marks, then by the name as written. */
    public static final Comparator<RequisitionRow> NAME_ORDER = Comparator
          .comparing((RequisitionRow row) -> RequisitionCatalog.sortKey(row.name()))
          .thenComparing(RequisitionRow::name);

    private static final Comparator<RequisitionRow> AS_WRITTEN = Comparator.comparing(RequisitionRow::name);
    /** Walk, run and jump points packed into one number that sorts as the three in turn. */
    private static final long MOVE_PLACE = 10_000;

    private final String messageKey;
    private final boolean numeric;

    RequisitionSort(String messageKey, boolean numeric) {
        this.messageKey = messageKey;
        this.numeric = numeric;
    }

    /** The message key of the key's name. */
    public String messageKey() {
        return messageKey;
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

    /** The key of the value the results show: the Point Value with {@code pv}, else the Battle Value. */
    public static RequisitionSort value(boolean pv) {
        return pv ? PV : BV;
    }

    /** Whether this is the key of a value, Battle or Point. */
    public boolean isValue() {
        return (this == BV) || (this == PV);
    }

    /** The order of rows in {@code sort}. */
    public static Comparator<RequisitionRow> order(TableSort<RequisitionSort> sort) {
        TableSort.Direction direction = sort.direction();
        return switch (sort.key()) {
            case NAME -> TableSort.comparator(row -> RequisitionCatalog.sortKey(row.name()), direction, AS_WRITTEN);
            case BV -> TableSort.comparator(RequisitionRow::bv, direction, NAME_ORDER);
            case PV -> TableSort.comparator(RequisitionRow::pv, direction, NAME_ORDER);
            case TONS -> TableSort.comparator(RequisitionRow::tons, direction, NAME_ORDER);
            case YEAR -> TableSort.comparator(row -> row.unit().getYear(), direction, NAME_ORDER);
            case MOVE -> TableSort.comparator(RequisitionSort::move, direction, NAME_ORDER);
            case ARMOR -> TableSort.comparator(RequisitionSort::armor, direction, NAME_ORDER);
            case ROLE -> TableSort.comparator(row -> blankAsNone(row.role()), String.CASE_INSENSITIVE_ORDER,
                  direction, NAME_ORDER);
            case LEVEL -> TableSort.comparator(RequisitionSort::level, direction, NAME_ORDER);
            case TYPE -> TableSort.comparator(row -> RequisitionRow.typeName(row.unit()),
                  String.CASE_INSENSITIVE_ORDER, direction, NAME_ORDER);
        };
    }

    /** {@code rows} in {@code sort}'s order; rows in name order come back as they are when that is the order. */
    public static List<RequisitionRow> sorted(List<RequisitionRow> rows, TableSort<RequisitionSort> sort) {
        if (sort.equals(BY_NAME)) {
            return rows;
        }
        return rows.stream().sorted(order(sort)).toList();
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

    /**
     * The rules level's rank, Introductory first and Unofficial last; a level the simple names do not hold comes after
     * them, and none goes last.
     */
    private static Integer level(RequisitionRow row) {
        String level = row.level();
        if (level.isBlank()) {
            return null;
        }
        for (int i = 0; i < TechConstants.T_SIMPLE_NAMES.length; i++) {
            if (TechConstants.T_SIMPLE_NAMES[i].equalsIgnoreCase(level.strip())) {
                return i;
            }
        }
        return TechConstants.T_SIMPLE_NAMES.length;
    }

    private static String blankAsNone(String text) {
        return ((text == null) || text.isBlank()) ? null : text;
    }
}
