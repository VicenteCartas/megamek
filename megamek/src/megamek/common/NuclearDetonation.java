/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common;

import java.io.Serializable;
import java.util.List;
import java.util.UUID;

import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.weapons.handlers.NukeStats;

/**
 * A server-confirmed blast, before its terrain and casualty updates: a scheduled nuclear strike, a Game Master's orbital
 * bombardment, or a conventional explosion that brought structures down ({@link Kind}). No shooter is invented.
 *
 * @param collapses animation metadata on the completion: every structure hex whose construction factor the blast took
 *                  to zero, chain explosions included, which the rules bring down at the phase's end
 */
public record NuclearDetonation(UUID id, int boardId, Coords coords, int elevation, int baseDamage,
      int degradation, int secondaryRadius, int craterDepth, List<BoardLocation> collapses, Kind kind)
      implements Serializable {
    /**
     * What went off: a nuclear strike (flash, mushroom, crater); an orbital bombardment (a lance of fire, no crater); a
     * conventional explosion (a fireball and its ring).
     */
    public enum Kind { NUCLEAR, ORBITAL, EXPLOSION }

    public NuclearDetonation {
        collapses = collapses == null ? List.of() : List.copyOf(collapses);
    }

    public NuclearDetonation(UUID id, int boardId, Coords coords, int elevation, int baseDamage, int degradation,
          int secondaryRadius, int craterDepth) {
        this(id, boardId, coords, elevation, baseDamage, degradation, secondaryRadius, craterDepth, List.of(), Kind.NUCLEAR);
    }

    /**
     * An orbital bombardment of {@code damage} over {@code radius} hexes, as the rules resolve it
     * (TWGameManager#doOrbitalBombardment): its damage falls by damage / (radius + 1) a hex, and it digs no crater.
     */
    public static NuclearDetonation orbitalBombardment(UUID id, int boardId, Coords coords, int elevation, int damage,
          int radius) {
        return new NuclearDetonation(id, boardId, coords, elevation, damage, Math.max(1, damage / (radius + 1)), 0, 0,
              List.of(), Kind.ORBITAL);
    }

    /**
     * A conventional explosion the rules resolve with {@code damage} at ground zero over {@code reach} hexes
     * (TWGameManager#doExplosion: its damage table's length), drawn as a blast falling off evenly over that reach. The
     * reach is exact; the damage at ground zero rounds up to a multiple of it. No crater.
     */
    public static NuclearDetonation explosion(UUID id, int boardId, Coords coords, int elevation, int damage, int reach) {
        int rings = Math.max(1, reach);
        int degradation = Math.max(1, (damage + rings - 1) / rings);
        return new NuclearDetonation(id, boardId, coords, elevation, degradation * rings, degradation, 0, 0, List.of(),
              Kind.EXPLOSION);
    }

    public NuclearDetonation withCollapses(List<BoardLocation> hexes) {
        return new NuclearDetonation(id, boardId, coords, elevation, baseDamage, degradation, secondaryRadius,
              craterDepth, hexes, kind);
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
