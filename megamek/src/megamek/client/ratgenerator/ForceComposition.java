/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ratgenerator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import jakarta.annotation.Nullable;
import megamek.client.ui.Messages;
import megamek.common.battleArmor.BattleArmor;
import megamek.common.units.ConvInfantry;
import megamek.common.units.Entity;
import megamek.common.units.EntityWeightClass;
import megamek.common.units.UnitType;

/**
 * What a generated force holds, by unit type and weight class, as the Force Generator's composition summary shows
 * it. Weight classes up to Light count as Light and above Heavy as Assault. Conventional infantry has no weight class
 * (every platoon reports Light), so its row shows a dash in the weight columns and is split by infantry class
 * instead. Battle Armor cells read "squads (troopers)". From Galaxy and Brigade echelon up the weight cells are
 * percentages of the row, since raw counts of hundreds of units are unreadable.
 */
public final class ForceComposition {
    /** Echelon at or above which the weight cells switch from counts to percentages. */
    static final int LARGE_ECHELON_PERCENT_THRESHOLD = 7;
    /** Shown in the weight cells of a unit type that has no weight class. */
    public static final String NO_WEIGHT_CELL = "-";
    private static final int NO_WEIGHT_COLUMN = 4;

    /**
     * One row of the summary.
     *
     * @param label  the unit type, such as "Mek" or "Infantry (Jump)", or the total row's label
     * @param weight the Light, Medium, Heavy and Assault cells
     * @param total  how many of them there are
     */
    public record Row(String label, List<String> weight, String total) {
        public Row {
            weight = List.copyOf(weight);
        }
    }

    /**
     * A unit type, split by class for conventional infantry. Rows sort by unit type, then infantry class in its
     * declared order, with an unclassified row first.
     *
     * @param unitType      the unit type
     * @param infantryClass the infantry class, or {@code null} for other unit types and unclassified platoons
     */
    public record RowKey(int unitType, @Nullable InfantryClass infantryClass) implements Comparable<RowKey> {
        private static final Comparator<RowKey> ORDER = Comparator.comparingInt(RowKey::unitType)
              .thenComparing(RowKey::infantryClass, Comparator.nullsFirst(Comparator.naturalOrder()));

        @Override
        public int compareTo(RowKey other) {
            return ORDER.compare(this, other);
        }

        /** @return the row label, e.g. "Mek" or "Infantry (Jump)" */
        public String label() {
            String unitTypeName = UnitType.getTypeDisplayableName(unitType);
            return (infantryClass == null) ? unitTypeName : unitTypeName + " (" + infantryClass.getDisplayName() + ")";
        }
    }

    private ForceComposition() {
    }

    /**
     * The summary of {@code force}: a row per unit type, then a total row; empty when it holds no units.
     */
    public static List<Row> rows(@Nullable ForceDescriptor force) {
        List<Row> rows = new ArrayList<>();
        if (force == null) {
            return rows;
        }
        ArrayList<Entity> entities = new ArrayList<>();
        force.addAllEntities(entities);
        // Per (row, column): [0] = units (squads for Battle Armor), [1] = Battle Armor troopers
        Map<RowKey, int[][]> counts = new TreeMap<>();
        for (Entity entity : entities) {
            int unitType = entity.getUnitType();
            int weightClass = entity.getWeightClass();
            int column;
            if (hasNoWeightClass(unitType)) {
                column = NO_WEIGHT_COLUMN;
            } else if (weightClass <= EntityWeightClass.WEIGHT_LIGHT) {
                column = 0;
            } else if (weightClass == EntityWeightClass.WEIGHT_MEDIUM) {
                column = 1;
            } else if (weightClass == EntityWeightClass.WEIGHT_HEAVY) {
                column = 2;
            } else {
                column = 3;
            }
            int[][] row = counts.computeIfAbsent(new RowKey(unitType, infantryClassOf(entity)),
                  key -> new int[NO_WEIGHT_COLUMN + 1][2]);
            row[column][0]++;
            if (entity instanceof BattleArmor battleArmor) {
                row[column][1] += battleArmor.getShootingStrength();
            }
        }
        Integer echelon = force.getEchelon();
        boolean asPercent = (echelon != null) && (echelon >= LARGE_ECHELON_PERCENT_THRESHOLD);
        int[] columnTotals = new int[4];
        int grandTotal = 0;
        for (Map.Entry<RowKey, int[][]> entry : counts.entrySet()) {
            int[][] row = entry.getValue();
            RowKey key = entry.getKey();
            boolean battleArmor = key.unitType() == UnitType.BATTLE_ARMOR;
            int typeTotal = 0;
            int typeTroopers = 0;
            for (int[] cell : row) {
                typeTotal += cell[0];
                typeTroopers += cell[1];
            }
            for (int column = 0; column < 4; column++) {
                columnTotals[column] += row[column][0];
            }
            grandTotal += typeTotal;
            List<String> weight = new ArrayList<>(4);
            for (int column = 0; column < 4; column++) {
                if (hasNoWeightClass(key.unitType())) {
                    weight.add(NO_WEIGHT_CELL);
                } else if (asPercent) {
                    weight.add(percent(row[column][0], typeTotal));
                } else {
                    weight.add(cell(row[column], battleArmor));
                }
            }
            // The total is always the plain count: the percentages say how the type is spread, this how many
            rows.add(new Row(key.label(), weight, cell(new int[] { typeTotal, typeTroopers }, battleArmor)));
        }
        if (!counts.isEmpty()) {
            List<String> weight = new ArrayList<>(4);
            for (int total : columnTotals) {
                weight.add(String.valueOf(total));
            }
            rows.add(new Row(Messages.getString("ForceGeneratorDialog.summary.total"), weight,
                  String.valueOf(grandTotal)));
        }
        return rows;
    }

    /** Conventional infantry reports every platoon as Light whatever it carries, so it has no weight class here. */
    private static boolean hasNoWeightClass(int unitType) {
        return unitType == UnitType.INFANTRY;
    }

    /** @return the class of a conventional infantry platoon, or {@code null} for any other unit */
    private static @Nullable InfantryClass infantryClassOf(Entity entity) {
        if (entity instanceof ConvInfantry platoon) {
            return InfantryClass.classify(platoon.getMovementMode(), platoon.isMounted());
        }
        return null;
    }

    private static String percent(int count, int total) {
        return (total <= 0) ? "" : Math.round(100.0 * count / total) + "%";
    }

    private static String cell(int[] squadsAndTroopers, boolean battleArmor) {
        int squads = squadsAndTroopers[0];
        return (battleArmor && (squads > 0)) ? squads + " (" + squadsAndTroopers[1] + ")" : String.valueOf(squads);
    }
}
