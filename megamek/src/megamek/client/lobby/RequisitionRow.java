/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.text.NumberFormat;
import java.util.Locale;

import megamek.client.ui.Messages;
import megamek.common.loaders.MekSummary;
import megamek.common.units.UnitRole;
import megamek.common.units.UnitType;

/**
 * One result of the Requisition view, as its slim card shows it: the name, a line of details (tonnage, type, tech base,
 * year), the columns that help to choose (movement, armor, role, rules level), the Battle Value and the Point Value. It
 * keeps the unit's summary, which the view hands back to add the unit or show its dossier.
 *
 * @param unit      the unit's summary in the unit cache
 * @param name      chassis and model
 * @param details   tonnage · type · tech base · year
 * @param bv        the Battle Value as the unit file has it, for the default skills
 * @param pv        the Alpha Strike Point Value
 * @param tons      the weight in tons
 * @param move      walk, run and jump points, as "4/6/3", or "4/6" without jump
 * @param armor     the armor points, or empty when the unit has none
 * @param role      the unit's role, or empty when it has none
 * @param level     the rules level, or empty
 * @param sortName  the name the results sort by ({@link RequisitionCatalog#sortKey})
 * @param type      the unit's type as players name it ({@link #typeName})
 * @param levelRank the rules level's rank, Introductory first and Unofficial last, or {@link #NO_LEVEL} without one
 */
public record RequisitionRow(MekSummary unit, String name, String details, int bv, int pv, double tons, String move,
      String armor, String role, String level, String sortName, String type, int levelRank) {

    /** The {@link #levelRank} of a unit without a rules level. */
    public static final int NO_LEVEL = -1;

    /** The row's columns between the name and the value, in their order; the last hide first when room runs out. */
    public enum Column {
        MOVE, ARMOR, ROLE, LEVEL;

        /** What {@code row} shows in this column. */
        public String of(RequisitionRow row) {
            return switch (this) {
                case MOVE -> row.move();
                case ARMOR -> row.armor();
                case ROLE -> row.role();
                case LEVEL -> row.level();
            };
        }

        public String messageKey() {
            return "Lobby.requisition.column." + name().toLowerCase(Locale.ROOT);
        }
    }

    /** The row of {@code unit}, with the keys it sorts by worked out once. */
    public static RequisitionRow of(MekSummary unit) {
        UnitRole role = RequisitionCatalog.roleOf(unit);
        String level = (unit.getTechLevel() == null) ? "" : unit.getTechLevel().strip();
        String type = typeName(unit);
        return new RequisitionRow(unit, unit.getName(), details(unit, type), unit.getBV(), unit.getPointValue(),
              unit.getTons(), move(unit.getWalkMp(), unit.getRunMp(), unit.getJumpMp()),
              unit.getTotalArmor() > 0 ? NumberFormat.getIntegerInstance().format(unit.getTotalArmor()) : "",
              role.hasRole() ? role.toString() : "", level, RequisitionCatalog.sortKey(unit.getName()), type,
              level.isBlank() ? NO_LEVEL : unit.getTechLevelCode());
    }

    /** "4/6/3", or "4/6" without jump: the columns' short movement. */
    public static String move(int walk, int run, int jump) {
        return (jump > 0) ? walk + "/" + run + "/" + jump : walk + "/" + run;
    }

    /** "70 t · BattleMek · IS · 3025": the tonnage, type, tech base and year of introduction. */
    public static String details(MekSummary unit) {
        return details(unit, typeName(unit));
    }

    private static String details(MekSummary unit, String type) {
        return Messages.getString("Lobby.requisition.details", tons(unit.getTons()), type,
              Messages.getString(RequisitionCatalog.TechBase.of(unit.getTechBase()).messageKey()),
              Integer.toString(unit.getYear()));
    }

    /** The unit's type as players name it; a support vehicle says so. */
    public static String typeName(MekSummary unit) {
        if (unit.isSupport()) {
            return Messages.getString("Lobby.requisition.type.support_vehicle");
        }
        int code = UnitType.determineUnitTypeCode(unit.getUnitType());
        return (code < 0) ? String.valueOf(unit.getUnitType()) : UnitType.getTypeDisplayableName(code);
    }

    /** Tons without a fraction when whole, else to the kilogram's useful part: "70", "0.5", "1.25". */
    public static String tons(double tons) {
        if (tons == Math.rint(tons)) {
            return Long.toString(Math.round(tons));
        }
        String text = String.format(Locale.ROOT, "%.3f", tons);
        while (text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }
}
