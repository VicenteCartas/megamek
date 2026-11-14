/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class GpuBoardStateTest {
    @Test
    void capturedGroundNeverAliasesAMutableTileBuilder() {
        var original = BoardScenes.scene(at -> BoardScenes.tile(at, BoardScene.Biome.NONE, 0));
        var builder = new ArrayList<>(original.tiles());
        var captured = new BoardScene(0, original.width(), original.height(), builder, List.of(), List.of(), -1, "", List.of());
        Coords at = new Coords(4, 4);
        builder.set(at.getX() * original.height() + at.getY(), BoardScenes.tile(at, BoardScene.Biome.NONE, -3));
        assertEquals(0, captured.tile(at).elevation());
        assertEquals(0, captured.groundScene().tile(at).elevation(), "Ground and live tiles share the immutable capture boundary");
    }

    @Test
    void collapseKeepsItsGroundAcrossOtherScenesFreshQueryCachesAndADistantCrater() {
        var plain = BoardScenes.scene(at -> BoardScenes.tile(at,
              at.getX() >= 3 && at.getX() <= 5 && at.getY() >= 1 && at.getY() <= 7
                    ? BoardScene.Surface.CONCRETE : BoardScene.Surface.GRASS,
              at.getX() >= 3 && at.getX() <= 5 && at.getY() >= 1 && at.getY() <= 7 ? 2 : 0, -1, 0));
        var unpinned = BoardConcrete.of(plain);
        Coords at = plain.tiles().stream().filter(tile -> tile.surface() == BoardScene.Surface.CONCRETE)
              .max(Comparator.comparingDouble(tile -> unpinned.corners(tile.coords()).stream()
                    .mapToDouble(shift -> Math.hypot(shift.x(), shift.y())).sum())).orElseThrow().coords();
        var building = new BoardScene.Feature("building-office", 0, 0, 0, 1, 2, 0, BoardScene.FeatureKind.BUILDING);
        var original = BoardScenes.replace(plain, plain.tile(at).withFeatures(List.of(building)));
        var state = new GpuBoardState();
        var settings = TerrainSettings.capture();
        var initial = state.update(original, settings);
        var fit = BoardConcrete.of(initial);
        assertNotEquals(unpinned.corners(at), fit.corners(at), "The building must pin a corner that would otherwise move");
        var firstQuery = new BoardSurface.Cache().get(initial, initial.tile(at));

        var cleared = state.update(BoardScenes.replace(original, original.tile(at).withStanding(List.of())), settings);
        assertTrue(cleared.tile(at).features().isEmpty(), "The live scene still presents the collapse");
        assertSame(initial.ground(), cleared.ground());
        // These unrelated queries used to evict the retained fit from a two-entry global cache.
        BoardConcrete.of(BoardScenes.scene(coords -> BoardScenes.tile(coords, BoardScene.Biome.NONE, 0)));
        BoardConcrete.of(BoardScenes.scene(coords -> BoardScenes.tile(coords, BoardScene.Biome.NONE, 1)));
        assertSame(fit, BoardConcrete.of(cleared));
        assertEquals(firstQuery.faces, new BoardSurface.Cache().get(cleared, cleared.tile(at)).faces,
              "A recreated query consumer must see the installed ground");

        var tile = cleared.tile(at);
        var blackIce = BoardScenes.newTile(at, tile.elevation(), tile.waterDepth(), tile.frozen(), tile.roadExits(),
              tile.surface(), tile.features(), tile.text(), tile.liquid(),
              tile.tileset(), tile.road(), tile.fireSmoke(), tile.biome(), tile.impassable(), true).since(tile);
        var iced = state.update(BoardScenes.replace(cleared, blackIce), settings);
        assertTrue(iced.groundScene().tile(at).blackIce(), "Weather must update the ground's appearance");
        assertEquals(fit.corners(at), BoardConcrete.of(iced).corners(at), "Black ice cannot release a collapsed building's ground pin");

        Coords crater = new Coords(8, 8);
        var cratered = state.update(BoardScenes.replace(iced, BoardScenes.tile(crater, BoardScene.Biome.NONE, -3)), settings);
        assertNotSame(initial.ground(), cratered.ground(), "Terraforming replaces its ground inputs");
        assertEquals(-3, cratered.groundScene().tile(crater).elevation());
        assertSame(iced.groundScene().tile(at), cratered.groundScene().tile(at), "The distant collapse keeps its support inputs");
        assertEquals(fit.corners(at), BoardConcrete.of(cratered).corners(at), "A distant crater cannot release the old building's pinned ground");
        assertEquals(firstQuery.faces, new BoardSurface.Cache().get(cratered, cratered.tile(at)).faces);
    }

    @Test
    void scorchRemovesAllScatterAndDecorationsWithoutRebuildingTheirGround() {
        Coords at = new Coords(4, 4);
        var plain = BoardScenes.scene(coords -> BoardScenes.tile(coords, BoardScene.Biome.NONE, 0));
        var prop = new BoardScene.Feature("street-lamp", 0, 0, 0, 1, 2, 0, BoardScene.FeatureKind.PROP);
        var plant = new BoardScene.Feature("scatter-plant", 0, 0, 0, 1, 1, 0, BoardScene.FeatureKind.SCATTER);
        var stone = new BoardScene.Feature("scatter-rock", 0, 0, 0, 1, 1, 0, BoardScene.FeatureKind.SCATTER);
        var bridge = new BoardScene.Feature("bridge", 0, 0, 0, 1, 2, 0, BoardScene.FeatureKind.PROP);
        var original = BoardScenes.replace(plain, plain.tile(at).withFeatures(List.of(prop, plant, stone, bridge)));
        var state = new GpuBoardState();
        var settings = TerrainSettings.capture();
        var initial = state.update(original, settings);

        var scorched = state.update(BoardScenes.replace(original, original.tile(at).withoutGroundCover(false)), settings);
        assertEquals(List.of(bridge), scorched.tile(at).features());
        assertTrue(scorched.tile(at).barren());
        assertSame(initial.ground(), scorched.ground(), "Removing decoration must not refit the terrain or its roads");
        assertEquals(original.tile(at).features(), scorched.groundScene().tile(at).features(),
              "The original objects' ground constraints survive their destruction");

        var collapsed = state.update(BoardScenes.replace(scorched, scorched.tile(at).withStanding(List.of())), settings);
        assertNotSame(scorched.ground(), collapsed.ground(), "A lost bridge must still update its carrier geometry");
    }

    @Test
    void sharedPresentationOwnerKeepsPlantingWhenRenderConsumersAreRecreated() {
        Coords at = new Coords(4, 4), unvisited = new Coords(6, 6);
        // A marsh: its reeds are planted; every blade of grass places itself on the ground map (GpuGroundMap).
        var original = BoardScenes.scene(coords -> BoardScenes.tile(coords, BoardScene.Biome.MARSH, 0));
        var tree = new BoardScene.Feature("tree-oak", 3, 4, 30, 1, 2, 0, BoardScene.FeatureKind.TREE);
        original = BoardScenes.replace(original, original.tile(at).withFeatures(List.of(tree)));
        var settings = TerrainSettings.capture();
        var state = new GpuBoardState();
        state.update(original, settings);
        var planting = state.planting();
        var roots = BoardPlantingTest.roots(planting, at);
        assertNotNull(roots);
        var expectedUnvisited = BoardPlantingTest.roots(new BoardPlanting(original), unvisited);

        var cleared = state.update(BoardScenes.replace(original, original.tile(at).withStanding(List.of())), settings);
        assertFalse(planting.allows(at, tree));
        // The GPU window and its query cache may be replaced; the BoardView keeps this presentation owner.
        new BoardSurface.Cache().get(cleared, cleared.tile(at));
        state.update(cleared, settings);
        assertSame(planting, state.planting());
        assertEquals(roots, BoardPlantingTest.roots(state.planting(), at), "Planting again gives the original roots");
        assertEquals(expectedUnvisited, BoardPlantingTest.roots(state.planting(), unvisited),
              "First visiting a hex later uses its original roots");

        var scorched = BoardScenes.replace(cleared, cleared.tile(at).withoutGroundCover(false));
        state.update(scorched, settings);
        assertNull(BoardPlantingTest.roots(planting, at));
        state.update(original, settings);
        assertNull(BoardPlantingTest.roots(state.planting(), at),
              "A new renderer or later snapshot cannot replant destroyed grass");
        assertFalse(state.planting().allows(at, tree), "A removed tree cannot be restored by presentation refresh");
    }
}
