/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class GpuRoadModeTest {
    @Test
    void classicAndEnhancedPrepareTheSameRoadPaintBetweenTheSameHeights() {
        Coords junction = new Coords(2, 2), upper = junction.translated(0);
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                Coords at = new Coords(x, y);
                int exits = at.equals(junction) ? 27 : 0;
                for (int d : new int[] { 0, 1, 3, 4 }) {
                    if (at.equals(junction.translated(d))) { exits = 1 << ((d + 3) % 6); }
                }
                tiles.add(BoardRoadTest.tile(at, exits == 0 ? BoardRoad.Kind.NONE : BoardRoad.Kind.PAVED,
                      exits, at.equals(upper) ? 1 : 0, BoardScene.Surface.GRASS));
            }
        }
        var scene = new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
        TerrainSettings settings = TerrainSettings.capture();
        settings.run(() -> {
            float floor = BoardGeometry.floor(scene);
            var classic = GpuTerrain.prepareColumns(scene, 0, 0, floor, Map.of(), Set.of(), Map.of(), Map.of(),
                  () -> { }, new TerrainLoadProgress());
            var enhanced = GpuTerrain.prepare(scene, 0, 0, floor, TerrainLod.FULL, Map.of(), settings, Set.of(), Map.of(),
                  () -> { }, new TerrainLoadProgress(), new GpuRoads.Masks());
            assertFalse(classic.roads().isEmpty(), "Classic installs generated roads rather than only tileset art");
            assertEquals(enhanced.roads().keySet(), classic.roads().keySet(), "Spill receivers belong to both views");
            boolean graded = false;
            for (var entry : enhanced.roads().entrySet()) {
                // Classic's whole-hex fan triangles can also take a patch whose bounds they only overlap: match by paint.
                var painted = new ArrayList<>(classic.roads().get(entry.getKey()));
                for (var expected : entry.getValue()) {
                    var patch = painted.stream().filter(p -> samePaint(expected, p)).findFirst().orElse(null);
                    assertNotNull(patch, "The same markings and feathering use the same mask at " + entry.getKey());
                    painted.remove(patch);
                    // Each view drapes its paint on its own ground, Classic's ramps straight and Enhanced's eased
                    // (BoardRoadGrade#of), so their triangles differ but join their neighbours at the same heights.
                    assertEquals(minZ(expected), minZ(patch), .001f, "The carriageway's foot differs at " + entry.getKey());
                    assertEquals(maxZ(expected), maxZ(patch), .001f, "The carriageway's top differs at " + entry.getKey());
                    graded |= maxZ(patch) - minZ(patch) > .001f;
                }
            }
            assertTrue(graded, "The fixture includes an actual road grade, not just flat paint");
        });
    }

    private static boolean samePaint(GpuTerrain.RoadPatch expected, GpuTerrain.RoadPatch patch) {
        Area difference = new Area(expected.patch().shape());
        difference.exclusiveOr(patch.patch().shape());
        return expected.source().equals(patch.source()) && expected.mask().equals(patch.mask())
              && expected.flat() == patch.flat() && Objects.equals(expected.patch().texture(), patch.patch().texture())
              && Objects.equals(expected.patch().tint(), patch.patch().tint())
              && Objects.equals(expected.patch().fade(), patch.patch().fade()) && difference.isEmpty();
    }

    private static float minZ(GpuTerrain.RoadPatch patch) {
        float result = Float.POSITIVE_INFINITY;
        for (var t : patch.triangles()) { result = Math.min(result, Math.min(t.a().z, Math.min(t.b().z, t.c().z))); }
        return result;
    }

    private static float maxZ(GpuTerrain.RoadPatch patch) {
        float result = Float.NEGATIVE_INFINITY;
        for (var t : patch.triangles()) { result = Math.max(result, Math.max(t.a().z, Math.max(t.b().z, t.c().z))); }
        return result;
    }
}
