/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;

import com.badlogic.gdx.utils.FloatArray;

/**
 * One marsh hex's reeds, planted by a terrain worker; every blade of grass and every crop places itself instead
 * ({@link GpuGroundCover}). During gameplay {@link BoardPlanting} keeps one original planting across chunk rebuilds
 * and detail changes; destruction only removes it. The editor replants changed support. Reeds grow thinned to what
 * their chunk's detail draws: memory follows detail rather than board size. None grows through paint: reeds grow by
 * one minus the opacity of the decals painting the hex's ground ({@code stamps}, {@link BoardDecals.Opacity}).
 */
record BoardPlants(FloatArray reeds) {
    /** Null for a hex that grows no reeds. */
    static BoardPlants plant(BoardScene scene, BoardScene.Tile tile, BoardTacticalGeometry.Surface support, TerrainLod lod,
          List<BoardDecals.Stamp> stamps) {
        // A Solaris elevator's steel platform covers its whole hex (BoardElevator).
        if (tile.ultraSublevel() || tile.backdrop().present() || BoardFeatures.shaft(tile) || lod == TerrainLod.DISTANT
              || BoardBiome.plantKind(scene, tile) != BoardScene.Biome.MARSH) { return null; }
        // Reed tiers start at 240, 64 and 12 projected pixels per hex; a chunk plants the tiers its detail lives at:
        // medium, and coarse on a late level (to about 79), up to the middle one, other coarse chunks the sparsest.
        return new BoardPlants(GpuBiomeVegetation.plantReeds(scene, tile, support,
              lod == TerrainLod.FULL ? 1 : lod == TerrainLod.MEDIUM || TerrainLod.lateDetail() ? .35f : .08f,
              BoardDecals.Opacity.of(stamps)));
    }
}
