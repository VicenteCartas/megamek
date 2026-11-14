/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.FloatArray;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

/** No ground cover grows through paint: every planter of {@link BoardPlants} honours {@link BoardDecals.Opacity}. */
class BoardPaintCoverTest {
    private static final Coords AT = new Coords(4, 4);

    private static List<BoardDecals.Stamp> stamp(BoardDecoration object) { return stamp(object, AT); }

    private static List<BoardDecals.Stamp> stamp(BoardDecoration object, Coords at) {
        return List.of(new BoardDecals.Stamp(at, object));
    }

    private static BoardTacticalGeometry.Surface support(BoardScene scene) { return support(scene, AT); }

    private static BoardTacticalGeometry.Surface support(BoardScene scene, Coords at) {
        return BoardTacticalGeometry.Surface.of(new BoardSurface(scene, scene.tile(at)), scene, BoardGeometry.floor(scene));
    }

    /** Roots as (x, y, z, rank) entries. */
    private static Set<List<Float>> roots(FloatArray roots) {
        Set<List<Float>> result = new HashSet<>();
        for (int i = 0; i < roots.size; i += 4) {
            result.add(List.of(roots.get(i), roots.get(i + 1), roots.get(i + 2), roots.get(i + 3)));
        }
        return result;
    }

    /** An opaque car park stretched over the whole hex and a little beyond. */
    private static final BoardDecoration COVER = new BoardDecoration("lot", "decal", "decal/car-park/straight-12", null, 0, 0, 0,
          false, 1, BoardDecoration.Placement.ground(), 0, false).withStretch(new BoardDecoration.Stretch(1.6, 10, 1));

    @Test
    void reedsLoseTheirRootsUnderOpaquePaint() {
        var scene = BoardScenes.scene(c -> BoardScenes.tile(c, BoardScene.Biome.MARSH, 0));
        var biome = scene.tile(AT);
        var covered = BoardDecals.Opacity.of(stamp(COVER, AT));
        assertTrue(GpuBiomeVegetation.plantReeds(scene, biome, support(scene), 1, null).size > 0, "The marsh grows reeds");
        assertEquals(0, GpuBiomeVegetation.plantReeds(scene, biome, support(scene), 1, covered).size);
    }

    @Test
    void reedsAvoidPlacedObjects() {
        var fountain = new BoardDecoration("fountain", "prop", "scenery/parks/fountain", null, 0, 0, 0, false, 1,
              BoardDecoration.Placement.ground(), 0);
        var feature = new BoardScene.Feature(fountain.asset(), 0, 0, 0, 1, 1, 0, BoardScene.FeatureKind.PROP, 0, false, fountain);
        for (var kind : List.of(BoardScene.Biome.MARSH)) {
            var at = AT;
            var plain = BoardScenes.scene(c -> BoardScenes.tile(c, kind, 0));
            var base = plain.tile(at);
            var placed = BoardScenes.replace(plain, BoardScenes.newTile(at, 0, -1, false, 0, base.surface(), List.of(feature), List.of(), BoardLiquid.NONE, null, BoardRoad.Kind.NONE,
                  BoardFireSmoke.NONE, kind));
            var obstacles = new BoardObstacles(placed, placed.tile(at));
            FloatArray bare = plantRoots(plain, kind, at), around = plantRoots(placed, kind, at);
            Set<List<Float>> kept = new HashSet<>(), all = new HashSet<>();
            for (int i = 0; i < bare.size; i += 4) { all.add(List.of(bare.get(i), bare.get(i + 1))); }
            int inside = 0;
            for (int i = 0; i < around.size; i += 4) {
                kept.add(List.of(around.get(i), around.get(i + 1)));
                if (obstacles.obstructs(new Vector3(around.get(i), around.get(i + 1), around.get(i + 2)), .01f, .01f)) { inside++; }
            }
            System.out.printf("COVER %s: %d plants, %d beside the fountain%n", kind, bare.size / 4, around.size / 4);
            assertEquals(0, inside, kind + ": no plant through the fountain");
            assertTrue(around.size > 0, kind + ": the rest of the hex still grows");
            // Reeds and plants are points that keep their places.
            assertTrue(around.size < bare.size && all.containsAll(kept), kind + ": the fountain clears its plants; the others stay");
        }
    }

    private static FloatArray plantRoots(BoardScene scene, BoardScene.Biome kind, Coords at) {
        return GpuBiomeVegetation.plantReeds(scene, scene.tile(at), support(scene, at), 1, null);
    }
}
