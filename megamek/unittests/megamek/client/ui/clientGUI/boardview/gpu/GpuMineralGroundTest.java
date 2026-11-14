/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.utils.FloatArray;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Hex;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class GpuMineralGroundTest {
    private static final Coords CENTER = new Coords(2, 2);
    private static final BoardScene.Feature DEPOSIT = new BoardScene.Feature(
          "minerals/deposit-0", 0, 0, 30, 9, .2f, 0, BoardScene.FeatureKind.METAL);

    @Test
    void soilHasSoftCoverageAndUsesTheActualUnevenGround() {
        BoardScene scene = scene("", List.of(DEPOSIT));
        var tile = scene.tile(CENTER);
        var surface = new BoardSurface(scene, tile);
        var paint = GpuMineralGround.prepare(scene, tile, surface);
        assertNotNull(paint);
        assertEquals("biome-soil", paint.patch().texture());
        assertTrue(paint.triangles().size() > 0);
        for (var triangle : paint.triangles()) {
            for (var point : List.of(triangle.a(), triangle.b(), triangle.c())) {
                assertEquals(surface.height(point.x, point.y) + paint.patch().lift() * BoardGeometry.hexScale(),
                      point.z, .002f, "The material coat follows the shared support, without adding a solid pad");
            }
        }
        var image = paint.mask().pixels();
        var alpha = new HashSet<Integer>();
        for (int pixel = 0; pixel < image.width() * image.height(); pixel++) { alpha.add(image.rgba(pixel) & 255); }
        assertTrue(alpha.contains(0));
        assertTrue(alpha.size() > 40, "The mask has a gradual edge rather than an opaque decal rim");
        assertTrue(alpha.stream().allMatch(value -> value <= 230), "Some original ground remains visible");
    }

    @Test
    void overlapsDoNotStackAndRoadRejectedPropsLeaveNoSoilPatch() {
        assertEquals(GpuMineralGround.coverage(List.of(DEPOSIT), 1, 2),
              GpuMineralGround.coverage(List.of(DEPOSIT, DEPOSIT), 1, 2));
        assertTrue(GpuMineralGround.coverage(List.of(DEPOSIT), 0, 0) > .85f);
        assertEquals(0, GpuMineralGround.coverage(List.of(DEPOSIT), 20, 20));
        BoardScene scene = scene("road:1:9", List.of(DEPOSIT));
        var tile = scene.tile(CENTER);
        assertNull(GpuMineralGround.prepare(scene, tile, new BoardSurface(scene, tile)),
              "The model and its soil share the same road-clearance decision");
    }

    @Test
    void emptyAndAerospaceHexesNeverReceiveSoil() {
        for (String terrain : List.of("", "space:1", "sky:1", "water:1")) {
            BoardScene scene = scene(terrain, terrain.isEmpty() ? List.of() : List.of(DEPOSIT));
            var tile = scene.tile(CENTER);
            assertNull(GpuMineralGround.prepare(scene, tile, new BoardSurface(scene, tile)));
        }
    }

    @Test
    void roadClearanceUsesTheSameModelUnitsAtEveryBoardScale() {
        var original = BoardGeometry.tuning();
        try {
            for (float scale : new float[] { .5f, 1, 2 }) {
                BoardGeometry.tune(new BoardGeometry.Tuning(scale, original.unitScale(), original.unitHeightScale(),
                      original.levelHeight(), original.gridShade(), original.multiHexUnitScale(),
                      original.transitions(), original.padding()));
                for (float x : new float[] { 18, 27 }) {
                    var deposit = new BoardScene.Feature(DEPOSIT.asset(), x, 0, 0, 9, .2f, 0,
                          BoardScene.FeatureKind.METAL);
                    BoardScene scene = scene("road:1:9", List.of(deposit));
                    var tile = scene.tile(CENTER);
                    var paint = GpuMineralGround.prepare(scene, tile, new BoardSurface(scene, tile));
                    assertEquals(x > 20, paint != null, "The same authored footprint has the same clearance at scale " + scale);
                }
            }
        } finally {
            BoardGeometry.tune(original);
        }
    }

    private static BoardScene scene(String terrain, List<BoardScene.Feature> features) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        var pool = new BoardScene.PixelPool();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                Coords at = new Coords(x, y);
                Hex hex = new Hex(at.equals(CENTER) ? 1 : 0, at.equals(CENTER) ? terrain : "", "");
                var artwork = new BoardArtwork.HexImage(at, List.of(), Map.of(), null);
                var tile = BoardScene.captureTile(hex, artwork, null, pool, unused -> null);
                tiles.add(tile.withFeatures(at.equals(CENTER) ? features : List.of()));
            }
        }
        return new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
