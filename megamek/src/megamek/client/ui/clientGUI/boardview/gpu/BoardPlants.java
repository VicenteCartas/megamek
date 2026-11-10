/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;

import com.badlogic.gdx.utils.FloatArray;

/**
 * One hex's ground cover, planted by a terrain worker on the hex's finished support and installed with it. Each
 * kind is present only when the chunk's detail level can show it, or is about to: memory follows detail rather than
 * board size. Reeds grow thinned to what their tiers draw, and grass, with its banks' rims and tundra's tussocks, and
 * the crops' plants from
 * {@link TerrainLod#MEDIUM}, so a chunk already carries its blades while its full-detail replacement is still being
 * built: grass only shows from 120 projected pixels per hex and crop plants from the 24 at which medium detail is
 * requested, and a medium chunk's roots keep their places and ranks on the full ground. A level that takes detail
 * late ({@link TerrainLod#lateDetail}) asks for medium only once they begin to show, so its coarse chunks carry
 * them too, grass only where the level draws meadows: its first zoom would otherwise wait on bare ground for each
 * chunk's rebuild. A chunk keeps what its level planted when the level changes on an open board, until it is rebuilt.
 * Nothing grows through paint: every kind grows by one minus the opacity of the decals painting the hex's ground
 * ({@code stamps}) and of its legacy paint overlay ({@link BoardDecals.Opacity}). Every part is null when the hex
 * grows none of it.
 */
record BoardPlants(FloatArray reeds, FloatArray grass, FloatArray wheat, FloatArray maize, FloatArray rim,
      FloatArray tussocks) {
    static BoardPlants plant(BoardScene scene, BoardScene.Tile tile, BoardTacticalGeometry.Surface support, TerrainLod lod,
          List<BoardDecals.Stamp> stamps) {
        // A Solaris elevator's steel platform covers its whole hex (BoardElevator).
        if (tile.ultraSublevel() || tile.backdrop().present() || BoardFeatures.shaft(tile) || lod == TerrainLod.DISTANT) {
            return null;
        }
        var kind = BoardBiome.plantKind(scene, tile);
        var paint = BoardDecals.Opacity.of(tile, stamps);
        boolean lateDetail = TerrainLod.lateDetail();
        // Reed tiers start at 240, 64 and 12 projected pixels per hex; a chunk plants the tiers its detail lives at:
        // medium, and coarse on a late level (to about 79), up to the middle one, other coarse chunks the sparsest.
        FloatArray reeds = kind == BoardScene.Biome.MARSH ? GpuBiomeVegetation.plantReeds(scene, tile, support,
              lod == TerrainLod.FULL ? 1 : lod == TerrainLod.MEDIUM || lateDetail ? .35f : .08f, paint) : null;
        // Earth tundra's tussocks grow with its grass.
        FloatArray tussocks = new FloatArray();
        // A coarse chunk prepared just before a level switch has no planting ground: no blades on its bare faces then.
        FloatArray grass = BoardVegetation.carriesGrass(lod) && GpuGroundCover.grows(scene, tile)
              && (lod != TerrainLod.COARSE || support.vegetation() != null)
              ? GpuGroundCover.plant(scene, tile, support, paint, tussocks) : null;
        boolean stalks = (lod != TerrainLod.COARSE || lateDetail) && kind == BoardScene.Biome.FIELD;
        FloatArray wheat = stalks ? GpuBiomeVegetation.plantStalks(scene, tile, support, paint, BoardFields.Crop.WHEAT) : null;
        FloatArray maize = stalks ? GpuBiomeVegetation.plantStalks(scene, tile, support, paint, BoardFields.Crop.MAIZE) : null;
        FloatArray rim = grass == null ? null : GpuGroundCover.plantRim(scene, tile, support, paint);
        return reeds == null && grass == null && wheat == null && maize == null && rim == null ? null
              : new BoardPlants(reeds, grass, wheat, maize, rim, tussocks.isEmpty() ? null : tussocks);
    }
}
