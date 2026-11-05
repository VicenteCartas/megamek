/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BoardBedLodTest {
    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void bedsKeepTheirFootprintDepthAndBoundedHeightError(TerrainLod lod) {
        var scene = scene(false, false);
        for (Coords at : List.of(new Coords(1, 2), new Coords(2, 3), new Coords(3, 3), new Coords(4, 3),
              new Coords(5, 3), new Coords(6, 3), new Coords(7, 3))) {
            var full = new BoardSurface(scene, scene.tile(at), TerrainLod.FULL);
            var surface = new BoardSurface(scene, scene.tile(at), lod);
            var canonical = bed(surface);
            assertEquals(bed(full), canonical, "Water-depth and support geometry must not depend on render LoD");
            var rendered = surface.renderBed(canonical);
            assertEquals(boundary(canonical), boundary(rendered), "Keep every shoreline and shared mouth vertex");
            assertTrue(rendered.size() <= canonical.size());
            assertEquals(area(canonical), area(rendered), .01, "No gaps, overlaps or missing bed area");
            var originalVertices = new HashSet<Vector3>();
            for (var face : canonical) { originalVertices.addAll(List.of(face.a(), face.b(), face.c())); }
            for (var face : rendered) {
                assertTrue(new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).z > 0,
                      "Simplified bed triangles must face up");
                assertTrue(originalVertices.containsAll(List.of(face.a(), face.b(), face.c())),
                      "Retained vertices must have canonical lighting normals");
            }
            float tolerance = BoardGeometry.width() / switch (lod) {
                case FULL -> 100000;
                case MEDIUM -> 512;
                case COARSE -> 128;
                case DISTANT -> 32;
            };
            for (var face : canonical) {
                var middle = new Vector3(face.a()).add(face.b()).add(face.c()).scl(1f / 3);
                for (var p : List.of(face.a(), face.b(), face.c(), middle,
                      new Vector3(face.a()).lerp(face.b(), .5f), new Vector3(face.b()).lerp(face.c(), .5f),
                      new Vector3(face.c()).lerp(face.a(), .5f))) {
                    assertEquals(p.z, BoardSurface.sampleHeight(rendered, p.x, p.y, Float.NaN), tolerance + .003f,
                          "Bed height error at " + at + " in " + lod);
                }
            }
            Vector3 center = BoardGeometry.center(at, 0);
            assertEquals(BoardGeometry.groundZ(scene.tile(at)),
                  BoardSurface.sampleHeight(rendered, center.x, center.y, Float.NaN), .001f,
                  "The unit anchor stays at game depth");
            assertTrue(canonical.size() <= 360, "Canonical beds already omit unnecessary interior rings");
        }
    }

    @Test
    void openLakeHasNoUnnecessaryInteriorBedRings() {
        var scene = scene(true, false);
        var at = new Coords(4, 3);
        var surface = new BoardSurface(scene, scene.tile(at), TerrainLod.DISTANT);
        var canonical = bed(surface);
        assertEquals(6, canonical.size(), "A level hex floor needs only its six corners and the unit anchor");
        assertEquals(canonical, surface.renderBed(canonical), "No separate dense support mesh is necessary");
    }

    @Test
    void seaportBedsKeepShoreContactsAndDepthWithSparseFloors() {
        var scene = BoardScenes.scene(new File("data/boards/unofficial/Unknown/seaportwithfueltanks.board"));
        int triangles = 0;
        for (var tile : scene.tiles()) {
            if (!tile.liquid().present()) { continue; }
            var surface = new BoardSurface(scene, tile);
            var faces = bed(surface);
            triangles += faces.size();
            assertTrue(faces.size() <= 84, "A shore contour needs one bank and one six-triangle floor");
            var contact = surface.waterGeometry().bedOutline();
            double footprint = 0;
            var center = BoardGeometry.center(tile.coords(), 0);
            for (int i = 0; i < contact.length; i++) {
                Vector3 a = contact[i], b = contact[(i + 1) % contact.length];
                footprint += (double) (a.x - center.x) * (b.y - center.y)
                      - (double) (a.y - center.y) * (b.x - center.x);
                for (Vector3 p : List.of(a, new Vector3(a).lerp(b, .5f))) {
                    assertEquals(p.z, BoardSurface.sampleHeight(faces, p.x, p.y, Float.NaN), .003f,
                          "The bed must meet every shore/depth contact at " + tile.coords());
                }
            }
            assertEquals(footprint, area(faces), .03, "No missing or overlapping bed footprint");
            assertEquals(BoardGeometry.groundZ(tile), surface.height(center.x, center.y), .001f,
                  "Picking and support retain the game depth");
        }
        assertTrue(triangles < 2000, "The shipped seaport's beds must not regain concentric tessellation");
        var open = new BoardSurface(scene, scene.tile(new Coords(3, 10)));
        assertEquals(6, bed(open).size(), "Seaport0411 has a plain level floor");
    }

    @Test
    void shallowBarsAndFrozenBedsRemainExact() {
        for (boolean frozen : new boolean[] { false, true }) {
            var scene = scene(true, frozen);
            var at = new Coords(frozen ? 4 : 2, 3);
            var surface = new BoardSurface(scene, scene.tile(at), TerrainLod.DISTANT);
            var canonical = bed(surface);
            assertSame(canonical, surface.renderBed(canonical), "Do not move exposed bars or frozen-water geometry");
        }
    }

    @Test
    void descendingStreamsKeepTheirWaterHeightInterpolation() {
        var scene = scene(false, false);
        var at = new Coords(5, 3);
        var surface = new BoardSurface(scene, scene.tile(at), TerrainLod.DISTANT);
        var canonical = bed(surface);
        assertSame(canonical, surface.renderBed(canonical), "Keep the varying water-height attribute on a sloping bed");
    }

    private static List<BoardSurface.Face> bed(BoardSurface surface) {
        return surface.faces.stream().filter(f -> f.finish() == BoardSurface.Finish.BED).toList();
    }

    private record Edge(Vector3 a, Vector3 b) { }

    private static Set<Edge> boundary(List<BoardSurface.Face> faces) {
        Set<Edge> edges = new HashSet<>();
        for (var face : faces) {
            for (var edge : List.of(new Edge(face.a(), face.b()), new Edge(face.b(), face.c()), new Edge(face.c(), face.a()))) {
                if (!edges.remove(new Edge(edge.b(), edge.a()))) { assertTrue(edges.add(edge), "No duplicate faces"); }
            }
        }
        return edges;
    }

    private static double area(List<BoardSurface.Face> faces) {
        double result = 0;
        for (var face : faces) {
            result += new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).z;
        }
        return result;
    }

    /** Pools, shallows, graded water, waterfalls and a twelve-level cliff beside a chunk boundary. */
    private static BoardScene scene(boolean lake, boolean frozen) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 10; x++) {
            for (int y = 0; y < 7; y++) {
                boolean land = !lake && (x == 0 || y == 0 || y == 6);
                int level = land ? x == 0 ? 12 : 1 : lake || x < 4 ? 0 : x < 7 ? x - 3 : 8;
                int depth = x == 2 ? 0 : x == 6 ? 8 : 1;
                tiles.add(new BoardScene.Tile(new Coords(x, y), level, land ? -1 : depth, frozen, 0,
                      BoardScene.Surface.SAND, null, null, List.of(), List.of(),
                      land ? BoardLiquid.NONE : BoardLiquid.WATER, null, true));
            }
        }
        return new BoardScene(0, 10, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    @Test
    void shoreBasinsKeepSparseUpwardGeometry() {
        for (boolean mouths : new boolean[] { false, true }) {
            var scene = BoardScenes.shores(1, mouths);
            var surface = new BoardSurface(scene, scene.tile(BoardScenes.WATER));
            for (var finish : List.of(BoardSurface.Finish.TOP, BoardSurface.Finish.BED)) {
                var faces = surface.faces.stream().filter(f -> f.finish() == finish).toList();
                assertTrue(!faces.isEmpty() && faces.size() <= (finish == BoardSurface.Finish.TOP ? 250 : 360),
                      "Keep the curved bank outline without unnecessary interior rings: " + finish + " " + faces.size());
                for (var face : faces) {
                    assertTrue(new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).z >= -.001f,
                          "Shore and bed triangles must not fold (vertical shore ends are allowed): " + face);
                }
                System.out.printf("MESH shore mouths=%s %s=%d%n", mouths, finish, faces.size());
            }
        }
    }

    @Test
    void transparentWaterKeepsItsVisibleBed() {
        BoardScene lava = BoardScenes.scene(new File("data/boards/Map Pack Volcanic/16x17 Dome Vent 2.board"));
        BoardScene scene = BoardWaterfallTest.withWater(lava);
        for (var tile : scene.tiles()) {
            if (!tile.liquid().present()) { continue; }
            BoardSurface surface = new BoardSurface(scene, tile);
            List<BoardSurface.Face> canonical = bed(surface);
            assertEquals(canonical, surface.renderBed(canonical));
        }
    }
}
