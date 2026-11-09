/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common;

import java.io.Serializable;
import java.util.List;
import java.util.UUID;

import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.weapons.handlers.NukeStats;

/**
 * A server-confirmed scheduled nuclear strike, before its terrain and casualty updates. No shooter is invented. An
 * orbital bombardment is sent as one too ({@code orbital}): its blast falls off over its radius, with no crater.
 *
 * @param collapses animation metadata on the completion: every structure hex whose construction factor the strike took
 *                  to zero, chain explosions included, which the rules bring down at the phase's end
 */
public record NuclearDetonation(UUID id, int boardId, Coords coords, int elevation, int baseDamage,
      int degradation, int secondaryRadius, int craterDepth, List<BoardLocation> collapses, boolean orbital)
      implements Serializable {
    public NuclearDetonation {
        collapses = collapses == null ? List.of() : List.copyOf(collapses);
    }

    public NuclearDetonation(UUID id, int boardId, Coords coords, int elevation, int baseDamage, int degradation,
          int secondaryRadius, int craterDepth) {
        this(id, boardId, coords, elevation, baseDamage, degradation, secondaryRadius, craterDepth, List.of(), false);
    }

    /**
     * An orbital bombardment of {@code damage} over {@code radius} hexes, as the rules resolve it
     * (TWGameManager#doOrbitalBombardment): its damage falls by damage / (radius + 1) a hex, and it digs no crater.
     */
    public static NuclearDetonation orbitalBombardment(UUID id, int boardId, Coords coords, int elevation, int damage,
          int radius) {
        return new NuclearDetonation(id, boardId, coords, elevation, damage, Math.max(1, damage / (radius + 1)), 0, 0,
              List.of(), true);
    }

    public NuclearDetonation withCollapses(List<BoardLocation> hexes) {
        return new NuclearDetonation(id, boardId, coords, elevation, baseDamage, degradation, secondaryRadius,
              craterDepth, hexes, orbital);
    }

    /** The resolved rules values, including custom strikes, as a fresh local effects snapshot. */
    public NukeStats stats() {
        var stats = new NukeStats();
        stats.baseDamage = baseDamage;
        stats.degradation = degradation;
        stats.secondaryRadius = secondaryRadius;
        stats.craterDepth = craterDepth;
        return stats;
    }
}
