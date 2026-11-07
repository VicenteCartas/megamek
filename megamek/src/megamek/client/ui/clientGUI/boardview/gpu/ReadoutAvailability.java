/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import jakarta.annotation.Nullable;
import megamek.client.ratgenerator.FactionRecord;
import megamek.client.ratgenerator.RATGenerator;
import megamek.client.ratgenerator.UnitAvailability;
import megamek.client.ui.entityreadout.EntityReadout.Row;
import megamek.common.eras.Era;
import megamek.common.units.Entity;

/**
 * A unit's availability to the Force Generator's factions as readout rows, as the unit selector's Faction Availability
 * tab shows it: per era a title, then a row for each faction that fields the unit then, by name, its name and its
 * availability.
 */
final class ReadoutAvailability {
    private ReadoutAvailability() {
    }

    /**
     * The rows of {@code entity}'s availability, none while the Force Generator is still loading or when it does not
     * know the unit. Reads the Force Generator's eras at first, which takes a while: not on the GPU thread.
     */
    static List<Row> of(Entity entity) {
        UnitAvailability<Era> availability = UnitAvailability.of(entity.getFullChassis(), entity.getModel());
        if (availability == null) {
            return List.of();
        }
        RATGenerator generator = RATGenerator.getInstance();
        return rows(availability, Era::name, code -> name(generator.getFaction(code), code));
    }

    /** The rows of {@code availability}, the eras and factions named by {@code eraName} and {@code factionName}. */
    static <E> List<Row> rows(UnitAvailability<E> availability, Function<E, String> eraName,
          Function<String, String> factionName) {
        List<Row> rows = new ArrayList<>();
        for (E era : availability.eras()) {
            List<Row> factions = new ArrayList<>();
            for (String faction : availability.factions()) {
                String text = availability.text(era, faction);
                if (!text.isEmpty()) {
                    factions.add(new Row(Row.Kind.TABLE_ROW,
                          List.of(factionName.apply(faction), text.replace("\n", ", "))));
                }
            }
            if (!factions.isEmpty()) {
                factions.sort(Comparator.comparing(row -> row.cells().get(0), String.CASE_INSENSITIVE_ORDER));
                rows.add(new Row(Row.Kind.TITLE, List.of(eraName.apply(era).replace("\n", " "))));
                rows.addAll(factions);
            }
        }
        return rows;
    }

    private static String name(@Nullable FactionRecord faction, String code) {
        return ((faction != null) && (faction.getName() != null)) ? faction.getName() : code;
    }
}
