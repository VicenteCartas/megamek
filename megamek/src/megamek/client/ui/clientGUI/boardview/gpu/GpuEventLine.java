/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.text;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.graphics.Color;
import megamek.client.ui.clientGUI.boardview.gpu.GpuPlaybackHistory.Step;
import megamek.client.ui.clientGUI.boardview.gpu.GpuReportLog.Entry;
import megamek.client.ui.gdx.UiTheme;
import megamek.common.ResolvedAttack;

/**
 * A playback event's who and what, from its report entry and step: the combat log's
 * cards show them as two lines, the dock's playback foot as one plain line (r2 2.4, ui.js evLine).
 */
final class GpuEventLine {
    private static final String SEPARATOR = " \u00B7 ";
    /** The what line's text size (.ev .tx > span); only the weapon's location is smaller (its small). */
    private static final float SIZE = 11.5f;

    /** A part of the what line in its own size and color (.ev .tx > span, its small, .hit, .bad). */
    record Part(String text, float size, Color color) {
        /** A part in the line's own size and color. */
        static Part plain(String text) {
            return new Part(text, SIZE, UiTheme.ACCENT);
        }

        /** The smaller weapon's location, which the card and the plain line set apart from the part before it. */
        boolean apart() {
            return size < SIZE && !text.isEmpty();
        }
    }

    private GpuEventLine() { }

    /**
     * "Atlas \u2192 King Crab", "Atlas \u00B7 piloting roll", "Atlas \u00B7 heat", else the entry's heading or unit.
     */
    static String who(Entry entry, int actor) {
        String name = name(entry, actor);
        return switch (entry.kind()) {
            case WEAPON, PHYSICAL -> {
                String target = name(entry, entry.targetId());
                yield name == null ? entry.heading() : target == null ? name
                      : text("GpuBoard.hud.log.attackTitle", name, target);
            }
            case PSR -> name == null ? entry.phase() : text("GpuBoard.hud.log.psrTitle", name);
            case HEAT -> name == null ? entry.phase() : text("GpuBoard.hud.log.heatTitle", name);
            default -> !entry.heading().isBlank() ? entry.heading() : name != null ? name : entry.phase();
        };
    }

    /** The name the report gives one of the entry's units, or null. */
    private static String name(Entry entry, int id) {
        return entry.units().stream().filter(unit -> unit.id() == id).map(GpuReportLog.Unit::name).findFirst()
              .orElse(null);
    }

    /**
     * The what line: the attack's weapon and location with HIT and its damage or MISS, and the destructions; NOT
     * FIRED and the reason; the roll's result and reasons; or MegaMek's own first line (the heat line, for one).
     */
    static List<Part> what(Entry entry, Step step) {
        List<Part> parts = new ArrayList<>();
        if (step != null && step.attack() != null) {
            GpuReportLog.CombatEvent attack = step.attack();
            boolean hit = attack.hit();
            parts.add(Part.plain(attack.weapon().isEmpty() ? physical(attack.kind()) : attack.weapon()));
            parts.add(new Part(attack.weaponLocation(), 10, UiTheme.MUTED));
            parts.add(Part.plain(SEPARATOR));
            parts.add(new Part(UiTheme.upper(text(hit ? "GpuBoard.hud.common.hit" : "GpuBoard.hud.common.miss")),
                  SIZE, hit ? UiTheme.MINT : UiTheme.ACCENT));
            if (hit) {
                parts.add(Part.plain(SEPARATOR + text("GpuBoard.hud.log.damage", attack.damage())));
            }
            destroyed(entry, parts);
        } else if (step != null && step.notFired() != null) {
            parts.add(Part.plain(UiTheme.upper(text("GpuBoard.hud.labels.notFired"))));
            parts.add(Part.plain(SEPARATOR + entry.notFired()));
        } else if (step != null) {
            boolean passed = step.roll().passed();
            parts.add(new Part(UiTheme.upper(text(passed ? "GpuBoard.hud.log.passed" : "GpuBoard.hud.log.failedFalls")),
                  SIZE, passed ? UiTheme.MINT : UiTheme.CORAL));
            if (!step.roll().reasons().isBlank()) {
                parts.add(Part.plain(SEPARATOR + step.roll().reasons()));
            }
        } else {
            // MegaMek's own words, e.g. the heat line's "gains 19 heat, sinks 4 heat and is now at 15 heat"; coral
            // when the entry is a heat alert.
            parts.add(new Part(entry.text().lines().findFirst().orElse(""), SIZE,
                  entry.heatAlert() ? UiTheme.CORAL : UiTheme.ACCENT));
            destroyed(entry, parts);
        }
        return parts;
    }

    /** "\u00B7 location destroyed" and "\u00B7 unit destroyed" in coral (.bad). */
    private static void destroyed(Entry entry, List<Part> parts) {
        if (entry.locationDestroyed()) {
            parts.add(Part.plain(SEPARATOR));
            parts.add(new Part(text("GpuBoard.hud.log.locationDestroyed"), SIZE, UiTheme.CORAL));
        }
        if (entry.unitDestroyed()) {
            parts.add(Part.plain(SEPARATOR));
            parts.add(new Part(text("GpuBoard.hud.log.unitDestroyed"), SIZE, UiTheme.CORAL));
        }
    }

    /** A physical attack's name, as MegaMek's physical attack buttons call it. */
    private static String physical(ResolvedAttack.Kind kind) {
        return switch (kind) {
            case PUNCH -> text("PhysicalDisplay.punch");
            case KICK -> text("PhysicalDisplay.kick");
            case PUSH -> text("PhysicalDisplay.push");
            case CLUB -> text("PhysicalDisplay.club");
            case CHARGE -> text("MovementDisplay.butCharge");
            case DFA -> text("MovementDisplay.butDfa");
            case RAM -> text("MovementDisplay.butRam");
            default -> "";
        };
    }

    /**
     * The step under the playback history's cursor as one plain line (ui.js evLine): who, without the piloting roll's
     * title, then the what line, as "Atlas \u2192 King Crab \u00B7 Medium Laser LA \u00B7 HIT \u00B7 5 damage" or
     * "King Crab \u00B7 PASSED \u00B7 took 20+ damage"; "" without a cursor or without the log's entry of its step.
     */
    static String current(GpuReportLog.Snapshot log, GpuPlaybackHistory history) {
        Step step = history.current();
        Entry entry = step == null ? null : log.entries().stream()
              .filter(candidate -> history.steps(candidate).contains(step)).findFirst().orElse(null);
        if (entry == null) {
            return "";
        }
        String unit = entry.kind() == GpuReportLog.Kind.PSR ? name(entry, step.actorId()) : null;
        StringBuilder line = new StringBuilder(unit != null ? unit : who(entry, step.actorId())).append(SEPARATOR);
        what(entry, step).forEach(part -> line.append(part.apart() ? " " : "").append(part.text()));
        return line.toString();
    }
}
