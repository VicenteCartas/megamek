/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import megamek.common.alphaStrike.ASStatsExporter;
import megamek.common.alphaStrike.AlphaStrikeElement;
import megamek.common.alphaStrike.AlphaStrikeHelper;
import megamek.common.alphaStrike.conversion.ASConverter;
import megamek.common.jacksonAdapters.MMUWriter;
import megamek.common.units.Entity;

/**
 * The Alpha Strike stats of a set of units, as the lobby's Alpha Strike view shows them: each unit converted with its
 * pilot, in the order the view sorts them by, with the stats a card shows, and the texts Copy, Copy Stats and Save
 * write. Units that cannot be converted are left out.
 * <p>
 * Swing thread.
 */
public final class AlphaStrikeStatsForm {
    private static final String COLUMN_SEPARATOR = "\t";
    private static final String INTERNAL_DELIMITER = ",";

    /** What the units can be sorted by, in the view's order. */
    public enum Sort {
        NAME("Name", Comparator.comparing(AlphaStrikeElement::getName, String.CASE_INSENSITIVE_ORDER)),
        TYPE("Type", Comparator.comparing(element -> element.getASUnitType().toString())),
        SIZE("SZ", Comparator.comparingInt(AlphaStrikeElement::getSize)),
        TMM("TMM", Comparator.comparingInt(AlphaStrikeElement::getTMM)),
        MOVE("MV (THR)", Comparator.comparing(AlphaStrikeElement::getMovementAsString)),
        ROLE("Role", Comparator.comparing(AlphaStrikeElement::getRole)),
        DAMAGE("Dmg S/M/L", Comparator.comparing(element -> element.getStandardDamage().toString())),
        OV("OV", Comparator.comparingInt(AlphaStrikeElement::getOV)),
        ARMOR("Arm", Comparator.comparingInt(AlphaStrikeElement::getFullArmor)),
        STRUCTURE("Str", Comparator.comparingInt(AlphaStrikeElement::getFullStructure)),
        THRESHOLD("Th", Comparator.comparingInt(AlphaStrikeElement::getThreshold)),
        SKILL("Skill", Comparator.comparingInt(AlphaStrikeElement::getSkill)),
        PV("PV", Comparator.comparingInt(AlphaStrikeElement::getPointValue));

        private final String label;
        private final Comparator<AlphaStrikeElement> order;

        Sort(String label, Comparator<AlphaStrikeElement> order) {
            this.label = label;
            this.order = order;
        }

        public String label() {
            return label;
        }
    }

    /** One of a unit's stats as its card shows it: its short label and its value. */
    public record Stat(String label, String value) { }

    private final List<AlphaStrikeElement> elements = new ArrayList<>();
    private Sort sort = Sort.NAME;
    private boolean descending;

    public AlphaStrikeStatsForm(Collection<Entity> units) {
        for (Entity unit : units) {
            if (ASConverter.canConvert(unit)) {
                AlphaStrikeElement element = ASConverter.convert(unit, true);
                if (element != null) {
                    elements.add(element);
                }
            }
        }
    }

    public Sort sort() {
        return sort;
    }

    public boolean descending() {
        return descending;
    }

    /** Sorts by {@code by}; sorting again by the same stat turns the order round. */
    public void sortBy(Sort by) {
        if (sort == Objects.requireNonNull(by)) {
            descending = !descending;
        } else {
            sort = by;
            descending = false;
        }
    }

    /** The units in the order shown; ties keep the name order. */
    public List<AlphaStrikeElement> elements() {
        Comparator<AlphaStrikeElement> order = descending ? sort.order.reversed() : sort.order;
        List<AlphaStrikeElement> sorted = new ArrayList<>(elements);
        sorted.sort(order.thenComparing(AlphaStrikeElement::getName, String.CASE_INSENSITIVE_ORDER));
        return sorted;
    }

    public boolean isEmpty() {
        return elements.isEmpty();
    }

    /** The unit's stats as its card shows them, leaving out those its type does not use. */
    public static List<Stat> stats(AlphaStrikeElement element) {
        List<Stat> stats = new ArrayList<>();
        stats.add(new Stat("TP", String.valueOf(element.getASUnitType())));
        stats.add(new Stat("SZ", String.valueOf(element.getSize())));
        if (!element.isAerospace()) {
            stats.add(new Stat("TMM", String.valueOf(element.getTMM())));
        }
        stats.add(new Stat(element.isAerospace() ? "THR" : "MV", element.getMovementAsString()));
        stats.add(new Stat("Role", String.valueOf(element.getRole())));
        if (!element.usesArcs()) {
            stats.add(new Stat("Dmg S/M/L", String.valueOf(element.getStandardDamage())));
        }
        if (element.usesOV()) {
            stats.add(new Stat("OV", String.valueOf(element.getOV())));
        }
        stats.add(new Stat("A", String.valueOf(element.getFullArmor())));
        stats.add(new Stat("S", String.valueOf(element.getFullStructure())));
        if (element.usesThreshold()) {
            stats.add(new Stat("TH", String.valueOf(element.getThreshold())));
        }
        stats.add(new Stat("Skill", String.valueOf(element.getSkill())));
        return stats;
    }

    /** The unit's special abilities, then for a unit with arcs each arc's, a line each. */
    public static List<String> specials(AlphaStrikeElement element) {
        List<String> lines = new ArrayList<>();
        String general = element.getSpecialsDisplayString(element);
        if (!general.isBlank()) {
            lines.add(general);
        }
        if (element.usesArcs()) {
            lines.add("FRONT(" + element.getFrontArc().getSpecialsExportString(", ", element) + ")");
            lines.add("LEFT(" + element.getLeftArc().getSpecialsExportString(", ", element) + ")");
            lines.add("RIGHT(" + element.getRightArc().getSpecialsExportString(", ", element) + ")");
            lines.add("REAR(" + element.getRearArc().getSpecialsExportString(", ", element) + ")");
        }
        return lines;
    }

    /** The total point value of the units. */
    public int totalPointValue() {
        return elements.stream().mapToInt(AlphaStrikeElement::getPointValue).sum();
    }

    /** The units' card data as tab-separated rows for a spreadsheet, under a header row, in the shown order. */
    public String cardsText() {
        StringBuilder result = new StringBuilder();
        for (String header : List.of("Chassis", "Model", "Type", "SZ", "TMM", "MV (THR)", "Role", "Dmg S/M/L", "OV",
              "Arm", "Str", "Th", "Skill", "PV", "Specials")) {
            result.append(header).append(COLUMN_SEPARATOR);
        }
        result.append("\n");
        for (AlphaStrikeElement element : elements()) {
            List<Function<AlphaStrikeElement, Object>> cells = List.of(
                  e -> "=\"" + e.getChassis() + "\"",
                  e -> "=\"" + e.getModel() + "\"",
                  AlphaStrikeElement::getASUnitType,
                  AlphaStrikeElement::getSize,
                  e -> e.isAerospace() ? "" : e.getTMM(),
                  AlphaStrikeElement::getMovementAsString,
                  AlphaStrikeElement::getRole,
                  e -> "=\"" + (e.usesArcs() ? "" : " " + e.getStandardDamage()) + "\"",
                  e -> e.usesOV() ? e.getOV() : "",
                  AlphaStrikeElement::getFullArmor,
                  AlphaStrikeElement::getFullStructure,
                  e -> e.usesThreshold() ? e.getThreshold() : "",
                  AlphaStrikeElement::getSkill,
                  AlphaStrikeElement::getPointValue);
            for (Function<AlphaStrikeElement, Object> cell : cells) {
                result.append(cell.apply(element)).append(COLUMN_SEPARATOR);
            }
            result.append(AlphaStrikeHelper.getSpecialsExportString(INTERNAL_DELIMITER, element)).append("\n");
        }
        return result.toString();
    }

    /** Each unit's stat block, as the Alpha Strike stats export writes it, in the shown order. */
    public String statsText() {
        StringBuilder result = new StringBuilder();
        for (AlphaStrikeElement element : elements()) {
            result.append(new ASStatsExporter(element).getStats()).append("\n");
        }
        return result.toString();
    }

    /** The name Save suggests: the first unit's general name, without its extension. */
    public String fileName() {
        return elements.isEmpty() ? "" : elements.getFirst().generalName();
    }

    /** Writes the units with their full stats as an MMU file. */
    public void save(File file) throws IOException {
        new MMUWriter().writeMMUFileFullStats(file, elements());
    }
}
