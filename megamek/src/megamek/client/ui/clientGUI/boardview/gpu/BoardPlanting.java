/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.badlogic.gdx.math.Matrix4;
import megamek.common.board.Coords;

/**
 * One game's original planting: what grew on each hex when the game began, and which hexes have lost it since.
 * Terrain detail and destruction cannot sow new roots on newly exposed ground, because every root is planted here,
 * on the original board, at the medium-detail support (the first tier carrying grass); drawing thins that set for the
 * camera, and refining the ground never changes these anchors. Planting a hex again gives the same roots, so none are
 * kept here: the chunk that draws a hex owns its roots ({@code GpuTerrain.TileMesh#plants}) and hands them to its
 * replacement, and memory follows the view instead of the board. Kept for the whole game are only the hexes that lost
 * their plants and the poses of the models already placed. The editor does not use this. No GPU resources or
 * authoritative game state are owned here.
 */
final class BoardPlanting {
    final BoardScene scene;
    private final Map<Coords, List<BoardDecals.Stamp>> paint;
    /** Read by the terrain workers; the render thread adds to it. */
    private final Set<Coords> removed = ConcurrentHashMap.newKeySet();
    // Render thread only, with the poses below.
    private final Set<Coords> uprooted = new HashSet<>();
    private final Map<Coords, Set<BoardScene.Feature>> remaining = new HashMap<>();
    private List<BoardScene.Tile> previous;
    /**
     * Poses of the models already placed, including rejected placements: a surviving member never retries after its
     * neighbour dies. The render thread writes them; the terrain workers read which trees are posed ({@link #posed}).
     */
    final Map<Coords, Map<BoardScene.Feature, Optional<Matrix4>>> poses = new ConcurrentHashMap<>();

    BoardPlanting(BoardScene scene) {
        this.scene = new BoardScene(scene.boardId(), scene.width(), scene.height(), scene.tiles(),
              List.of(), List.of(), -1, "", List.of()).withGround(scene.ground());
        paint = BoardDecals.index(this.scene);
        previous = scene.tiles();
        remove(scene);
    }

    /** Destruction can remove a planting, including a crater's unsupported roots, but cannot restore it. */
    void remove(BoardScene current) {
        for (var tile : current.tiles()) {
            var original = scene.tile(tile.coords());
            if (tile.barren() || tile.bare() || tile.elevation() != original.elevation()
                  || tile.waterDepth() != original.waterDepth()) { removed.add(tile.coords()); }
            if (tile.elevation() != original.elevation() || tile.waterDepth() != original.waterDepth()) {
                uprooted.add(tile.coords());
            }
            if (tile != previous.get(tile.coords().getX() * scene.height() + tile.coords().getY())) {
                remaining.computeIfAbsent(tile.coords(), key -> new HashSet<>(features(scene, original)))
                      .retainAll(features(current, tile));
            }
        }
        previous = current.tiles();
    }

    private static List<BoardScene.Feature> features(BoardScene scene, BoardScene.Tile tile) {
        return BoardMultiHexBuilding.withTrees(scene, tile, BoardFeatures.fittedControls(scene, tile));
    }

    boolean allows(Coords at, BoardScene.Feature feature) {
        // Scorched grass is cosmetic; standing woods remain until the game's feature snapshot removes them.
        return !uprooted.contains(at) && remaining.computeIfAbsent(at,
              key -> new HashSet<>(features(scene, scene.tile(key)))).contains(feature);
    }

    /**
     * The hex's original support, which its planted models and field stones stand on, under the caller's settings.
     * Built on request and not kept: a chunk build holds it only while it places that hex.
     */
    BoardSurface support(Coords at) { return new BoardSurface(scene, scene.tile(at)); }

    boolean cleared(Coords at) { return removed.contains(at); }

    /** The poses of a hex's placed models, which the render thread adds to. */
    Map<BoardScene.Feature, Optional<Matrix4>> poses(Coords at) {
        return poses.computeIfAbsent(at, key -> new ConcurrentHashMap<>());
    }

    /**
     * Whether a model of a hex already has its pose: it stands there again whatever the detail, so its support is not
     * read again (GpuTerrain#plantSupports).
     */
    boolean posed(Coords at, BoardScene.Feature feature) {
        var placed = poses.get(at);
        return placed != null && placed.containsKey(feature);
    }

    /**
     * The original roots of {@code tiles}, by hex, for a chunk drawn at {@code lod}: where its chunk holds a hex's roots
     * already ({@code kept}) those, else the hex planted again on its original support. The supports of one call are
     * finished in a batch, so adjacent hexes do not each rebuild the same banks and walls; {@code installed} are the
     * chunk's own surfaces, which stand in for the original ones where a hex's geometry never changed. A hex that lost
     * its plants, or whose chunk is too far to draw any, has no entry. {@code settings} are the build's, which every
     * task of the batch runs under.
     */
    Map<Coords, BoardPlants> roots(List<BoardScene.Tile> tiles, TerrainLod lod, Map<Coords, BoardSurface> installed,
          Map<Coords, BoardPlants> kept, TerrainSettings settings, Runnable check) {
        Map<Coords, BoardPlants> result = new ConcurrentHashMap<>();
        List<BoardScene.Tile> needed = new ArrayList<>();
        for (var current : tiles) {
            var tile = scene.tile(current.coords());
            if (removed.contains(tile.coords()) || lod == TerrainLod.DISTANT
                  || BoardBiome.plantKind(scene, tile) != BoardScene.Biome.MARSH) { continue; }
            BoardPlants held = kept.get(tile.coords());
            if (held != null) { result.put(tile.coords(), held); } else { needed.add(tile); }
        }
        if (needed.isEmpty()) { return result; }
        float floor = settings.call(() -> BoardGeometry.floor(scene));
        Map<Coords, BoardSurface> surfaces = new ConcurrentHashMap<>();
        var obstacles = new BoardObstacles.Cache(scene);
        needed.parallelStream().forEach(tile -> settings.run(() -> obstacles.run(() -> {
            check.run();
            var existing = lod == TerrainLod.MEDIUM ? installed.get(tile.coords()) : null;
            surfaces.put(tile.coords(), existing != null && tile.sameGeometry(existing.tile) ? existing
                  : new BoardSurface(scene, tile, TerrainLod.MEDIUM));
        })));
        needed.parallelStream().forEach(tile -> settings.run(() -> obstacles.run(() -> {
            check.run();
            var support = BoardTacticalGeometry.Surface.of(surfaces.get(tile.coords()), scene, floor, surfaces);
            // The map fixes every candidate at load: the full root set, whatever detail asked first.
            BoardPlants planted = BoardPlants.plant(scene, tile, support, TerrainLod.FULL,
                  paint.getOrDefault(tile.coords(), List.of()));
            if (planted != null) { result.put(tile.coords(), planted); }
        })));
        return result;
    }
}
