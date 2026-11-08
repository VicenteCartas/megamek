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
    void bladesStayOffAnEmblemYetGrowInItsTransparentCornersAndKeepTheirPlacesElsewhere() {
        var scene = BoardScenes.scene(c -> BoardScenes.tile(c, BoardScene.Surface.GRASS, 0, -1, 0));
        var tile = scene.tile(AT);
        var cross = new BoardDecoration("cross", "decal", "decal/emblems/red-cross", null, .05, 0, 20, false, .5,
              BoardDecoration.Placement.ground(), 0, false);
        var paint = BoardDecals.Opacity.of(tile, stamp(cross));
        var bare = roots(GpuGroundCover.plant(scene, tile, support(scene), null));
        var painted = roots(GpuGroundCover.plant(scene, tile, support(scene), paint));
        assertTrue(bare.containsAll(painted), "Paint only removes roots: the others keep their places and ranks");
        var uv = BoardDecals.paintUv(cross);
        float x0 = BoardGeometry.centerX(AT) + (float) cross.x() * BoardGeometry.width(), y0 = BoardGeometry.centerY(AT);
        int corners = 0;
        for (var root : bare) {
            float opacity = paint.at(root.get(0), root.get(1));
            if (painted.contains(root)) {
                assertTrue(opacity < .5f, "No blade through the emblem");
                float u = uv.u(root.get(0) - x0, root.get(1) - y0), v = uv.v(root.get(0) - x0, root.get(1) - y0);
                if (u > 0 && u < 1 && v > 0 && v < 1) { corners++; }
            } else {
                assertTrue(opacity > 0, "A root outside the paint and its margin stays");
            }
        }
        assertTrue(painted.size() < bare.size() - 200, "The emblem clears its grass: " + painted.size() + " of " + bare.size());
        assertTrue(corners > 50, "Grass grows inside the emblem's square where its image is transparent: " + corners);
    }

    @Test
    void bankRimsReedsAndCropsLoseTheirRootsUnderOpaquePaint() {
        var bank = BoardScenes.scene(c -> BoardScenes.tile(c, BoardScene.Surface.GRASS, c.getX() < 5 ? 2 : 0, -1, 0));
        var tile = bank.tile(AT);
        var paint = BoardDecals.Opacity.of(tile, stamp(COVER));
        assertTrue(GpuGroundCover.plantRim(bank, tile, support(bank), null).size > 0, "The bank's rim grows blades");
        assertNull(GpuGroundCover.plantRim(bank, tile, support(bank), paint));
        for (var kind : List.of(BoardScene.Biome.MARSH, BoardScene.Biome.FIELD)) {
            var scene = BoardScenes.scene(c -> BoardScenes.tile(c, kind, 0));
            // Crops stand only in a field of wheat or maize.
            var at = kind == BoardScene.Biome.FIELD ? BoardScenes.CROPS : AT;
            var biome = scene.tile(at);
            var covered = BoardDecals.Opacity.of(biome, stamp(COVER, at));
            if (kind == BoardScene.Biome.MARSH) {
                assertTrue(GpuBiomeVegetation.plantReeds(scene, biome, support(scene), 1, null).size > 0, "The marsh grows reeds");
                assertEquals(0, GpuBiomeVegetation.plantReeds(scene, biome, support(scene), 1, covered).size);
            } else {
                assertTrue(BoardScenes.cropPlants(scene, biome, support(scene, at), null).size > 0, "The field grows crops");
                assertEquals(0, BoardScenes.cropPlants(scene, biome, support(scene, at), covered).size);
            }
        }
    }

    @Test
    void reedsAndCropsAvoidPlacedObjects() {
        var fountain = new BoardDecoration("fountain", "prop", "scenery/parks/fountain", null, 0, 0, 0, false, 1,
              BoardDecoration.Placement.ground(), 0);
        var feature = new BoardScene.Feature(fountain.asset(), 0, 0, 0, 1, 1, 0, BoardScene.FeatureKind.PROP, 0, false, fountain);
        for (var kind : List.of(BoardScene.Biome.MARSH, BoardScene.Biome.FIELD)) {
            // Crops stand only in a field of wheat or maize.
            var at = kind == BoardScene.Biome.FIELD ? BoardScenes.CROPS : AT;
            var plain = BoardScenes.scene(c -> BoardScenes.tile(c, kind, 0));
            var base = plain.tile(at);
            var placed = BoardScenes.replace(plain, new BoardScene.Tile(at, 0, -1, false, 0, base.surface(),
                  null, null, List.of(feature), List.of(), BoardLiquid.NONE, null, true, BoardRoad.Kind.NONE,
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
        return kind == BoardScene.Biome.MARSH ? GpuBiomeVegetation.plantReeds(scene, scene.tile(at), support(scene, at), 1, null)
              : BoardScenes.cropPlants(scene, scene.tile(at), support(scene, at), null);
    }
}
