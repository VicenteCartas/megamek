/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;

class BoardGroundInputsTest {
    @Test
    void aNuclearConversionToRoughUsesTheNewGroundWithoutTheDestroyedBuildingObstacle() {
        var at = new megamek.common.board.Coords(4, 4);
        var scene = BoardScenes.scene(coords -> BoardScenes.tile(coords, BoardScene.Biome.NONE, 0));
        var building = new BoardScene.Feature(GpuBuildingTest.ASSET, 0, 0, 0, 1, 2, 0, BoardScene.FeatureKind.BUILDING);
        scene = BoardScenes.replace(scene, scene.tile(at).withFeatures(List.of(building)));
        var state = new GpuBoardState();
        var settings = TerrainSettings.capture();
        state.update(scene, settings);
        var rough = new megamek.common.Hex(0, "rough:1", "", at);
        var converted = BoardScenes.replace(scene, scene.tile(at).withFeatures(
              BoardFeatures.capture(rough, at, java.util.Map.of())));
        var shown = state.update(converted, settings);
        assertEquals(new BoardSurface(converted, converted.tile(at)).faces,
              new BoardSurface(shown, shown.tile(at)).faces,
              "A genuine ground conversion must not fit new rocks around a destroyed building");
    }

    @Test
    void destroyedFuelTanksAndIndustryKeepTheGroundOutlineTheyOriginallyPinned() {
        var plain = BoardScenes.scene(at -> BoardScenes.tile(at,
              at.getX() >= 3 && at.getX() <= 5 ? BoardScene.Surface.CONCRETE : BoardScene.Surface.GRASS,
              at.getX() >= 3 && at.getX() <= 5 ? 2 : 0, -1, 0));
        var unpinned = BoardConcrete.of(plain);
        var at = plain.tiles().stream().filter(tile -> tile.surface() == BoardScene.Surface.CONCRETE)
              .max(Comparator.comparingDouble(tile -> unpinned.corners(tile.coords()).stream()
                    .mapToDouble(shift -> Math.hypot(shift.x(), shift.y())).sum())).orElseThrow().coords();
        for (var kind : List.of(BoardScene.FeatureKind.FUEL_TANK, BoardScene.FeatureKind.INDUSTRIAL)) {
            var feature = new BoardScene.Feature("building-office", 0, 0, 0, 1, 2, 0, kind);
            var original = BoardScenes.replace(plain, plain.tile(at).withFeatures(List.of(feature)));
            var state = new GpuBoardState();
            var settings = TerrainSettings.capture();
            var initial = state.update(original, settings);
            var corners = BoardConcrete.of(initial).corners(at);
            assertNotEquals(unpinned.corners(at), corners, "The structure must actually constrain the outline");
            var removed = state.update(BoardScenes.replace(original, original.tile(at).withStanding(List.of())), settings);
            assertTrue(removed.tile(at).features().isEmpty());
            assertSame(initial.ground(), removed.ground(), "Destruction is an object change: " + kind);
            assertEquals(corners, BoardConcrete.of(removed).corners(at));
        }
    }
}
