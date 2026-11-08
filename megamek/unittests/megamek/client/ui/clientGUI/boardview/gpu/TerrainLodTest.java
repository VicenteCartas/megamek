/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class TerrainLodTest {
    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void cliffTopsDoNotFoldAtBrokenRims(TerrainLod detail) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 11; x++) {
            for (int y = 0; y < 7; y++) {
                var family = x % 2 == 0 ? BoardScene.Surface.SAND : BoardScene.Surface.ROCK;
                tiles.add(new BoardScene.Tile(new Coords(x, y), (3 * x + 2 * y) % 8, -1, false, 0, family, null, null,
                      List.of(), List.of(), BoardLiquid.NONE, null, true));
            }
        }
        var scene = new BoardScene(0, 11, 7, tiles, List.of(), List.of(), -1, "", List.of());
        int compared = 0;
        for (var tile : tiles) {
            var surface = new BoardSurface(scene, tile, detail);
            for (var face : surface.faces) {
                if (face.finish() != BoardSurface.Finish.TOP) { continue; }
                float projected = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).z;
                assertTrue(projected >= -.001f, "Broken cliff rims must not fold over at " + tile.coords() + ": " + face);
                compared++;
            }
        }
        assertTrue(compared > 100, "Exercise varied cliff rims and chunk boundaries");
    }

    @Test
    void distantGroundKeepsCoverageAndFullDetailChunkSeams() {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 10; x++) {
            for (int y = 0; y < 7; y++) {
                tiles.add(new BoardScene.Tile(new Coords(x, y), 1, -1, false, 0, BoardScene.Surface.GRASS, null, null,
                      List.of(), List.of(), BoardLiquid.NONE, null, true));
            }
        }
        var scene = new BoardScene(0, 10, 7, tiles, List.of(), List.of(), -1, "", List.of());
        Coords at = new Coords(7, 3);
        var distant = new BoardSurface(scene, scene.tile(at), TerrainLod.DISTANT);
        var full = new BoardSurface(scene, scene.tile(at), TerrainLod.FULL);
        double[] area = new double[2];
        for (int i = 0; i < 2; i++) {
            for (var face : (i == 0 ? distant : full).faces) {
                if (face.finish() != BoardSurface.Finish.TOP) { continue; }
                float projected = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).z;
                assertTrue(projected > 0, "Ground triangles must face up");
                area[i] += projected;
            }
        }
        assertEquals(area[1], area[0], .01, "Simplification must retain the entire ground footprint");
        assertEquals(6, distant.faces.stream().filter(f -> f.finish() == BoardSurface.Finish.TOP).count());
        assertEquals(6, full.faces.stream().filter(f -> f.finish() == BoardSurface.Finish.TOP).count(),
              "Full detail must not add geometry to a flat top");
        Vector3 center = BoardGeometry.center(at, 1);
        for (int direction = 0; direction < 6; direction++) {
            Coords other = at.translated(direction);
            if (TerrainLod.sameChunk(at, other)) { continue; }
            var neighbor = new BoardSurface(scene, scene.tile(other), TerrainLod.FULL);
            int edge = Math.floorMod(1 - direction, 6);
            for (int step = 1; step < TerrainLod.FULL.steps; step++) {
                Vector3 p = BoardGeometry.corner(at, 1, edge)
                      .lerp(BoardGeometry.corner(at, 1, edge + 1), step / (float) TerrainLod.FULL.steps);
                Vector3 inside = new Vector3(p).lerp(center, .002f);
                Vector3 outside = new Vector3(p).lerp(BoardGeometry.center(other, 1), .002f);
                assertEquals(distant.height(inside.x, inside.y), neighbor.height(outside.x, outside.y), .05f,
                      "Distant ground must meet its full-detail neighbor");
                for (float radius : new float[] { .25f, .5f, .75f }) {
                    Vector3 sample = new Vector3(center).lerp(p, radius);
                    assertEquals(full.height(sample.x, sample.y), distant.height(sample.x, sample.y),
                          BoardGeometry.width() / 24, "Height error stays below a quarter pixel at a six-pixel hex width");
                }
            }
        }
    }

    @Test
    void waterCliffsMeetAcrossMixedDetailChunkBoundaries() {
        var previous = BoardRelief.tuning();
        try {
            BoardScenes.tune(true);
            Coords land = new Coords(7, 3);
            var scene = waterScene(land);
            for (TerrainLod lod : TerrainLod.values()) {
                var upper = new BoardSurface(scene, scene.tile(land), lod);
                var walls = upper.walls(scene, BoardGeometry.floor(scene));
                for (int direction = 0; direction < 6; direction++) {
                    Coords at = land.translated(direction);
                    var water = new BoardSurface(scene, scene.tile(at), at.getX() < 8 ? lod : TerrainLod.FULL);
                    int edge = Math.floorMod(1 - direction, 6);
                    var submerged = water.faces.stream().filter(face -> face.landEdge() == (edge + 3) % 6
                          && face.finish() == BoardSurface.Finish.WALL)
                          .flatMap(face -> List.of(face.a(), face.b(), face.c()).stream()).toList();
                    for (var face : walls) {
                        if (face.landEdge() != edge) { continue; }
                        for (var p : List.of(face.a(), face.b(), face.c())) {
                            if (Math.abs(p.z) > .001f) { continue; }
                            assertTrue(submerged.stream().anyMatch(q -> q.epsilonEquals(p, .001f)),
                                  lod + ": submerged cliff must meet every upper-cliff vertex at " + p);
                        }
                    }
                }
            }
        } finally { BoardRelief.tune(previous); }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void waterFieldDoesNotIntroduceASeamBetweenDetailLevels(boolean rough) {
        var scene = waterScene(new Coords(7, 3), rough);
        Map<Coords, BoardSurface> left = new HashMap<>(), right = new HashMap<>();
        for (var tile : scene.tiles()) {
            (tile.coords().getX() < 8 ? left : right).put(tile.coords(),
                  new BoardSurface(scene, tile, tile.coords().getX() < 8 ? TerrainLod.DISTANT : TerrainLod.FULL));
        }
        var a = GpuWaterShader.Field.prepare(scene, Map.of(), left);
        var b = GpuWaterShader.Field.prepare(scene, Map.of(), right);
        assertEquals(a.spacing(), b.spacing());
        int compared = 0;
        for (int y = Math.max(a.firstY(), b.firstY()); y < Math.min(a.firstY() + a.height(), b.firstY() + b.height()); y++) {
            for (int x = Math.max(a.firstX(), b.firstX()); x < Math.min(a.firstX() + a.width(), b.firstX() + b.width()); x++) {
                for (int channel = 0; channel < 4; channel++) {
                    int ai = ((y - a.firstY()) * a.width() + x - a.firstX()) * 4 + channel;
                    int bi = ((y - b.firstY()) * b.width() + x - b.firstX()) * 4 + channel;
                    assertEquals(Byte.toUnsignedInt(a.pixels()[ai]), Byte.toUnsignedInt(b.pixels()[bi]), 1,
                          "Shared water-field sample " + x + "," + y + " channel " + channel);
                    compared++;
                }
            }
        }
        assertTrue(compared > 100, "The fields must overlap at a real chunk boundary");
    }

    private static BoardScene waterScene(Coords land) {
        return waterScene(land, false);
    }

    private static BoardScene waterScene(Coords land, boolean rough) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 10; x++) {
            for (int y = 0; y < 7; y++) {
                var at = new Coords(x, y);
                boolean dry = at.equals(land);
                var features = !dry && rough ? List.of(new BoardScene.Feature("rough-boulder", 8, 5, 17, 1.8f, .7f, 0,
                      BoardScene.FeatureKind.BOULDER)) : List.<BoardScene.Feature>of();
                tiles.add(new BoardScene.Tile(at, dry ? 5 : 0, dry ? -1 : 1, false, 0, BoardScene.Surface.SAND, null,
                      null, features, List.of(), dry ? BoardLiquid.NONE : BoardLiquid.WATER, null, true));
            }
        }
        return new BoardScene(0, 10, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    @Test
    void screenSizeSelectsDetailWithHysteresis() {
        assertEquals(TerrainLod.FULL, TerrainLod.select(200, null));
        assertEquals(TerrainLod.MEDIUM, TerrainLod.select(40, null));
        assertEquals(TerrainLod.COARSE, TerrainLod.select(10, null));
        assertEquals(TerrainLod.DISTANT, TerrainLod.select(3, null));
        assertEquals(TerrainLod.FULL, TerrainLod.select(60, TerrainLod.FULL));
        assertEquals(TerrainLod.MEDIUM, TerrainLod.select(68, TerrainLod.MEDIUM));
        assertEquals(TerrainLod.FULL, TerrainLod.select(72, TerrainLod.MEDIUM));
        assertEquals(TerrainLod.MEDIUM, TerrainLod.select(55, TerrainLod.FULL));
        assertEquals(TerrainLod.FULL, TerrainLod.select(200, TerrainLod.DISTANT));
        assertEquals(TerrainLod.DISTANT, TerrainLod.select(3, TerrainLod.FULL));
    }
}
