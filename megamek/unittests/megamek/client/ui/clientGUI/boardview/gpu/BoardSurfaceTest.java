/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class BoardSurfaceTest {
    @Test
    void supportHeightKeepsScaleToleranceAndIgnoresIceAndDressing() {
        var original = TerrainSettings.capture();
        for (float scale : new float[] { .5f, 1, 2 }) {
            var settings = new TerrainSettings(new BoardGeometry.Tuning(scale, 1, 1, 18, .8f),
                  original.relief(), original.revision(), original.terrainRevision());
            try (var ignored = TerrainSettings.use(settings)) {
                var ground = new BoardSurface.Face(new Vector3(0, 0, 3), new Vector3(2 * scale, 0, 3),
                      new Vector3(0, 2 * scale, 3), BoardSurface.Finish.BANK);
                var ice = new BoardSurface.Face(new Vector3(0, 0, 9), new Vector3(2 * scale, 0, 9),
                      new Vector3(0, 2 * scale, 9), BoardSurface.Finish.ICE);
                var dressing = new BoardSurface.Face(ice.a(), ice.b(), ice.c(), BoardSurface.Finish.DRESSING);
                var faces = List.of(ice, ground, dressing);
                assertEquals(3, BoardSurface.sampleHeight(faces, .5f * scale, .5f * scale, -7), .000001f);
                assertEquals(3, BoardSurface.sampleHeight(faces, -.0008f * scale, .5f * scale, -7), .000001f);
                assertEquals(-7, BoardSurface.sampleHeight(faces, -.0012f * scale, .5f * scale, -7));
                assertEquals(-7, BoardSurface.sampleHeight(List.of(ice, dressing), .5f * scale, .5f * scale, -7));
            }
        }
    }

    @Test
    void completedNeighborSurfacesPreserveExactWallsAndOutsideChunkFallbacks() {
        for (BoardScene scene : List.of(BoardScenes.roadScene(false), BoardScenes.roadScene(true), randomBoard(new Random(7419)),
              BoardScenes.dropScene(BoardScene.Surface.SAND))) {
            Map<Coords, BoardSurface> surfaces = new java.util.concurrent.ConcurrentHashMap<>();
            scene.tiles().parallelStream().forEach(tile -> surfaces.put(tile.coords(), new BoardSurface(scene, tile)));
            Map<Coords, BoardSurface> partial = new HashMap<>(surfaces);
            partial.keySet().removeIf(coords -> (coords.getX() & 1) == 0);
            float floor = -6 * BoardGeometry.LEVEL;
            for (BoardSurface surface : surfaces.values()) {
                var expected = surface.sides(scene, floor);
                assertEquals(expected, surface.sides(scene, floor, surfaces), "Finished neighbor edges");
                assertEquals(expected, surface.sides(scene, floor, partial), "Missing neighbors use the original path");
            }
            // Match the renderer's barrier: all tops exist before independent wall builders read their neighbors.
            surfaces.values().parallelStream().forEach(surface -> {
                var expected = new BoardSurface(scene, surface.tile).walls(scene, floor);
                assertEquals(expected, surface.walls(scene, floor, surfaces), "Finished neighbor walls");
            });
        }
    }

    @Test
    void supportCacheReusesGeometryAcrossPosesAndInvalidatesForTerrainAndTuning() {
        var cache = new BoardSurface.Cache();
        var scene = BoardScenes.roadScene(false);
        var first = cache.get(scene, scene.tile(BoardScenes.FIRST));
        assertSame(first, cache.get(scene.withUnits(List.of()), scene.tile(BoardScenes.FIRST)));
        var edited = BoardScenes.roadScene(false, 1, false, false, 2);
        var cliff = cache.get(edited, edited.tile(BoardScenes.FIRST));
        assertEquals(0, cliff.ramps);
        assertNotSame(first, cliff);
        var tuning = BoardGeometry.tuning();
        try {
            BoardGeometry.tune(new BoardGeometry.Tuning(tuning.hexScale() * 1.2f, tuning.unitScale(), tuning.unitHeightScale(),
                  tuning.levelHeight(), tuning.gridShade(), tuning.multiHexUnitScale()));
            var resized = cache.get(edited, edited.tile(BoardScenes.FIRST));
            assertNotSame(cliff, resized);
            var center = BoardGeometry.center(BoardScenes.FIRST, 2);
            assertEquals(center.z, resized.height(center.x, center.y), .001f);
        } finally { BoardGeometry.tune(tuning); }
        cache.clear();
        assertNotSame(cliff, cache.get(edited, edited.tile(BoardScenes.FIRST)));
    }

    @Test
    void unitsStandOnTheGroundDrawnBesideWaterOnRandomBoards() {
        // Over land beside water the ground drawn may be a neighbour's: a water hex's bank or bed, or a step's slope
        // lying over the land's footprint. Units stand on the highest of it (within a quarter level, where a
        // neighbour's rim rock stands a little over the land's own top).
        BoardScenes.withTransitions(true, () -> {
            Random random = new Random(3);
            for (int trial = 0; trial < 20; trial++) {
                BoardScene scene = randomBoard(random);
                BoardSurface.Cache cache = new BoardSurface.Cache();
                float floor = BoardGeometry.floor(scene);
                for (BoardScene.Tile tile : scene.tiles()) {
                    List<BoardScene.Tile> around = new ArrayList<>(List.of(tile));
                    for (int direction = 0; direction < 6; direction++) {
                        BoardScene.Tile other = scene.tile(tile.coords().translated(direction));
                        if (other != null) { around.add(other); }
                    }
                    if (tile.liquid().present() || around.stream().noneMatch(other -> other.liquid().present())) {
                        continue;
                    }
                    // The tops, banks and beds drawn over the hex by it and its neighbours, and their steps' slopes.
                    float cx = BoardGeometry.centerX(tile.coords()), cy = BoardGeometry.centerY(tile.coords());
                    List<BoardSurface.Face> tops = new ArrayList<>(), slopes = new ArrayList<>();
                    for (BoardScene.Tile other : around) {
                        BoardSurface surface = cache.get(scene, other);
                        tops.addAll(over(surface.faces, cx, cy));
                        slopes.addAll(over(BoardTacticalGeometry.lying(surface.walls(scene, floor)), cx, cy));
                    }
                    for (float dx = -42; dx <= 42; dx += 6) {
                        for (float dy = -36; dy <= 36; dy += 6) {
                            float x = cx + dx * BoardGeometry.HEX_SCALE, y = cy + dy * BoardGeometry.HEX_SCALE;
                            if (!BoardGeometry.contains(tile.coords(), x, y)) { continue; }
                            float drawn = BoardSurface.sampleHeight(tops, x, y, Float.NEGATIVE_INFINITY);
                            for (BoardSurface.Face face : slopes) { drawn = Math.max(drawn, face.height(x, y)); }
                            if (Float.isFinite(drawn)) {
                                assertEquals(drawn, UnitLandingSupports.ground(scene, x, y, cache),
                                      BoardGeometry.LEVEL / 4, "Trial " + trial + ", " + tile.coords() + " at " + dx
                                            + ", " + dy);
                            }
                        }
                    }
                }
            }
        });
    }

    @Test
    void bedsNeverFoldAndAnchorsKeepTheirGameHeightsOnRandomBoards() {
        Random random = new Random(3);
        for (int trial = 0; trial < 20; trial++) {
            BoardScene scene = randomBoard(random);
            for (BoardScene.Tile tile : scene.tiles()) {
                BoardSurface surface = new BoardSurface(scene, tile);
                Vector3 center = BoardGeometry.center(tile.coords(), tile.elevation());
                assertEquals(BoardGeometry.groundZ(tile), surface.height(center.x, center.y), .01f,
                      "Anchor of " + tile.coords() + " in trial " + trial);
                for (BoardSurface.Face face : surface.faces) {
                    if (face.finish() != BoardSurface.Finish.BED) { continue; }
                    Vector3 n = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                    assertTrue(n.z > 0, "A bed of " + tile.coords() + " folds in trial " + trial + ": " + face);
                }
            }
        }
    }

    /** The faces whose bounds reach over the footprint of the hex centred at (x, y). */
    private static List<BoardSurface.Face> over(List<BoardSurface.Face> faces, float x, float y) {
        float w = BoardGeometry.WIDTH / 2, h = BoardGeometry.HEIGHT / 2;
        return faces.stream().filter(face -> Math.max(face.a().x, Math.max(face.b().x, face.c().x)) >= x - w
              && Math.min(face.a().x, Math.min(face.b().x, face.c().x)) <= x + w
              && Math.max(face.a().y, Math.max(face.b().y, face.c().y)) >= y - h
              && Math.min(face.a().y, Math.min(face.b().y, face.c().y)) <= y + h).toList();
    }

    /**
     * A random seven by seven board of detailed ground of the first four surfaces: land up to three levels high, and
     * two hexes in five water up to two levels deep at level 0 or 1.
     */
    private static BoardScene randomBoard(Random random) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                boolean wet = random.nextInt(5) < 2;
                tiles.add(new BoardScene.Tile(new Coords(x, y), wet ? random.nextInt(2) : random.nextInt(4),
                      wet ? random.nextInt(3) : -1, false, 0, BoardScene.Surface.values()[random.nextInt(4)], null,
                      null, List.of(), List.of(), wet ? BoardLiquid.WATER : BoardLiquid.NONE, null,
                      true));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
