/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import megamek.common.force.Force;
import megamek.common.game.Game;
import megamek.common.jacksonAdapters.MMUWriter;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFFormationConverter;
import megamek.common.strategicBattleSystems.SBFUnit;

/**
 * The Strategic BattleForce formations of a set of forces, as the lobby's SBF view shows them: each formation with
 * its stats and its units, the text Copy writes and the file Save writes. Forces that cannot be converted are left
 * out.
 * <p>
 * Swing thread.
 */
public final class SbfStatsForm {
    private static final String COLUMN_SEPARATOR = "\t";

    /** One stat as the view shows it: its short label and its value. */
    public record Stat(String label, String value) { }

    private final List<SBFFormation> formations;
    private boolean showElements;

    public SbfStatsForm(Collection<Force> forces, Game game) {
        this(forces.stream().map(force -> new SBFFormationConverter(force, game).convert())
              .filter(Objects::nonNull).toList());
    }

    SbfStatsForm(List<SBFFormation> formations) {
        this.formations = List.copyOf(formations);
    }

    public List<SBFFormation> formations() {
        return formations;
    }

    public boolean isEmpty() {
        return formations.isEmpty();
    }

    /** Whether the Alpha Strike elements of each unit show under it. */
    public boolean showElements() {
        return showElements;
    }

    public void showElements(boolean show) {
        showElements = show;
    }

    /** The formation's stats, as its record sheet's top line. */
    public static List<Stat> stats(SBFFormation formation) {
        return List.of(new Stat("Type", String.valueOf(formation.getType())),
              new Stat("Size", String.valueOf(formation.getSize())),
              new Stat("Move", formation.getMovement() + formation.getMovementCode()),
              new Stat("Jump", String.valueOf(formation.getJumpMove())),
              new Stat("T. Move", formation.getTrspMovement() + formation.getTrspMovementCode()),
              new Stat("TMM", String.valueOf(formation.getTmm())),
              new Stat("Tactics", String.valueOf(formation.getTactics())),
              new Stat("Morale", String.valueOf(formation.getMorale())),
              new Stat("Skill", String.valueOf(formation.getSkill())),
              new Stat("PV", String.valueOf(formation.getPointValue())));
    }

    /** The unit's stats, as its record sheet's line. */
    public static List<Stat> stats(SBFUnit unit) {
        return List.of(new Stat("Type", String.valueOf(unit.getType())),
              new Stat("Size", String.valueOf(unit.getSize())),
              new Stat("Move", unit.getMovement() + unit.getMovementCode()),
              new Stat("Jump", String.valueOf(unit.getJumpMove())),
              new Stat("T. Move", unit.getTrspMovement() + unit.getTrspMovementCode()),
              new Stat("TMM", String.valueOf(unit.getTmm())),
              new Stat("Arm", String.valueOf(unit.getArmor())),
              new Stat("Dmg", String.valueOf(unit.getDamage())),
              new Stat("Skill", String.valueOf(unit.getSkill())),
              new Stat("PV", String.valueOf(unit.getPointValue())));
    }

    public static String specials(SBFFormation formation) {
        return formation.getSpecialsDisplayString(", ", formation);
    }

    public static String specials(SBFUnit unit) {
        return unit.getSpecialsDisplayString(", ", unit);
    }

    /** The total point value of the formations. */
    public int totalPointValue() {
        return formations.stream().mapToInt(SBFFormation::getPointValue).sum();
    }

    /** The formations and their units as tab-separated rows for a spreadsheet. */
    public String clipboardText() {
        StringBuilder result = new StringBuilder();
        row(result, "SBF Formation", "Type", "Size", "Move", "JUMP", "T. Move", "TMM", "Tactics", "Morale", "Skill",
              "PV", "Specials");
        for (SBFFormation formation : formations) {
            row(result, formation.getName(), formation.getType(), formation.getSize(),
                  formation.getMovement() + formation.getMovementCode(), formation.getJumpMove(),
                  formation.getTrspMovement() + formation.getTrspMovementCode(), formation.getTmm(),
                  formation.getTactics(), formation.getMorale(), formation.getSkill(), formation.getPointValue(),
                  specials(formation));
            row(result, "Unit", "", "", "", "", "", "", "Arm", "Dmg", "", "", "", "");
            for (SBFUnit unit : formation.getUnits()) {
                row(result, unit.getName(), unit.getType(), unit.getSize(), unit.getMovement() + unit.getMovementCode(),
                      unit.getJumpMove(), unit.getTrspMovement() + unit.getTrspMovementCode(), unit.getTmm(),
                      unit.getArmor(), "=\"" + unit.getDamage() + "\"", unit.getSkill(), unit.getPointValue(),
                      specials(unit), "");
            }
        }
        return result.toString();
    }

    private static void row(StringBuilder result, Object... cells) {
        for (Object cell : cells) {
            result.append(cell).append(COLUMN_SEPARATOR);
        }
        result.append("\n");
    }

    /** The name Save suggests: the first formation's general name, without its extension. */
    public String fileName() {
        return formations.isEmpty() ? "" : formations.getFirst().generalName();
    }

    /** Writes the formations as an MMU file. */
    public void save(File file) throws IOException {
        new MMUWriter().writeMMUFile(file, new ArrayList<>(formations));
    }
}
