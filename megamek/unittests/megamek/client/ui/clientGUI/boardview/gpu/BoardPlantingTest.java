/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class BoardPlantingTest {
    private static final Coords AT = new Coords(4, 4);

    /** The hex's roots as a chunk at {@code lod} asks for them, holding {@code kept} already. */
    static BoardPlants roots(BoardPlanting planting, Coords at, TerrainLod lod, Map<Coords, BoardPlants> kept) {
        return planting.roots(List.of(planting.scene.tile(at)), lod, Map.of(), kept, TerrainSettings.capture(), () -> { })
              .get(at);
    }

    static BoardPlants roots(BoardPlanting planting, Coords at) { return roots(planting, at, TerrainLod.FULL, Map.of()); }

    @Test
    void collapseAndDetailChangesKeepTheOriginalRootsIncludingUnvisitedHexes() {
        // A marsh: its reeds are planted; every blade of grass places itself on the ground map (GpuGroundMap).
        var board = BoardScenes.scene(at -> BoardScenes.tile(at, BoardScene.Biome.MARSH, 0));
        var tree = new BoardScene.Feature("tree-oak", 3, 4, 30, 1, 2, 0, BoardScene.FeatureKind.TREE);
        board = BoardScenes.replace(board, board.tile(AT).withFeatures(List.of(tree)));
        var planting = new BoardPlanting(board);
        var original = roots(planting, AT);
        assertNotNull(original);
        assertTrue(original.reeds().size > 0);
        assertEquals(original, roots(planting, AT, TerrainLod.MEDIUM, Map.of()), "Every detail plants the same roots");
        assertNull(roots(planting, AT, TerrainLod.DISTANT, Map.of(AT, original)), "A distant chunk holds none");
        assertSame(original, roots(planting, AT, TerrainLod.MEDIUM, Map.of(AT, original)),
              "A chunk's replacement takes over the roots it drew: nothing is planted or uploaded again");
        var cleared = BoardScenes.replace(board, board.tile(AT).withStanding(List.of()));
        planting.remove(cleared);
        assertEquals(original, roots(planting, AT), "A collapse leaves the original roots");
        assertFalse(planting.allows(AT, tree));
        planting.remove(board);
        assertFalse(planting.allows(AT, tree), "Destroyed plants cannot be restored by a later capture");
        assertEquals(original, roots(planting, AT));
        var unvisited = AT.translated(0);
        var later = new BoardPlanting(board);
        later.remove(cleared);
        assertEquals(roots(planting, unvisited), roots(later, unvisited), "Unvisited roots still use the original map");
        // One batch finishes the supports of all its hexes together; each hex's roots are those it gets alone.
        var batch = planting.roots(board.tiles(), TerrainLod.FULL, Map.of(), Map.of(), TerrainSettings.capture(), () -> { });
        assertEquals(original, batch.get(AT), "Shared preparation must preserve the original placement");
    }

    @Test
    void destructionCannotReplantButAnExplicitNewEditorPlantingCan() {
        var board = BoardScenes.scene(at -> BoardScenes.tile(at, BoardScene.Biome.MARSH, 0));
        var tree = new BoardScene.Feature("tree-oak", 3, 4, 30, 1, 2, 0, BoardScene.FeatureKind.TREE);
        board = BoardScenes.replace(board, board.tile(AT).withFeatures(List.of(tree)));
        var planting = new BoardPlanting(board);
        var original = roots(planting, AT);
        assertNotNull(original);
        planting.remove(BoardScenes.replace(board, board.tile(AT).withoutGroundCover(false)));
        assertNull(roots(planting, AT));
        assertNull(roots(planting, AT, TerrainLod.FULL, Map.of(AT, original)), "Not even the roots its chunk still holds");
        assertTrue(planting.allows(AT, tree), "Scorched grass must not remove surviving game trees");
        planting.remove(board);
        assertNull(roots(planting, AT));
        assertNotNull(roots(new BoardPlanting(board), AT));
    }

    @Test
    void scorchClearsEveryScatterKindWithoutRemovingGameTreesOrChangingGround() {
        var board = BoardScenes.scene(at -> BoardScenes.tile(at, BoardScene.Biome.NONE, 0));
        var tree = new BoardScene.Feature("tree-oak", 3, 4, 30, 1, 2, 0, BoardScene.FeatureKind.TREE);
        var scattered = new java.util.ArrayList<BoardScene.Feature>();
        for (String asset : List.of("scatter-rock", "scatter-slab", "scatter-dry-grass", "scatter-plant",
              BoardFungus.SCATTER.getFirst())) {
            scattered.add(new BoardScene.Feature(asset, 2, 3, 0, 1, 1, 0, BoardScene.FeatureKind.SCATTER));
        }
        var features = new java.util.ArrayList<>(scattered);
        features.add(tree);
        board = BoardScenes.replace(board, board.tile(AT).withFeatures(features));
        var planting = new BoardPlanting(board);
        var burned = board.tile(AT).withoutGroundCover(false);
        assertEquals(List.of(tree), burned.features());
        assertTrue(BoardGround.sameInputs(board.tile(AT), burned));
        var alreadyBurned = new BoardPlanting(BoardScenes.replace(board, burned));
        assertTrue(alreadyBurned.cleared(AT), "Loading an already scorched map must not recreate field stones");
        assertNull(roots(alreadyBurned, AT));
        planting.remove(BoardScenes.replace(board, burned));
        planting.remove(board);
        assertNull(roots(planting, AT));
        assertTrue(planting.allows(AT, tree));
        for (var scatter : scattered) { assertFalse(planting.allows(AT, scatter)); }
    }
}
