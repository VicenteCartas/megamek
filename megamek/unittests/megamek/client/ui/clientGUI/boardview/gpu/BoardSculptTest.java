/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Watertightness, level preservation, bounded deformation and picking of the canonical terrain sculpt, with hex
 * transitions off and on.
 */
class BoardSculptTest {
    /** Plateaus, an isolated spire, a ridge, three-level junctions, a sunken basin; no water or roads. */
    private static final String[] DRY = {
          "00000000000",
          "00033300000",
          "00333330010",
          "03333332000",
          "03333322050",
          "00330331000",
          "00021110000",
          "02000000aa0",
          "02000000ab0",
          "00000000000",
    };

    private static BoardScene scene(String[] layout, BoardScene.Surface family) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        int width = layout[0].length(), height = layout.length;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                char cell = layout[y].charAt(x);
                int level = cell == 'a' ? -1 : cell == 'b' ? -2 : cell - '0';
                tiles.add(new BoardScene.Tile(new Coords(x, y), level, -1, false, 0,
                      (x + y) % 4 == 0 ? BoardScene.Surface.GRASS : family, null, null,
                      List.of(), List.of(), BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, width, height, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private record Key(long x, long y, long z) {
        static Key of(Vector3 p) {
            return new Key(Math.round(p.x * 1000), Math.round(p.y * 1000), Math.round(p.z * 1000));
        }
    }

    @ParameterizedTest(name = "transitions {0}")
    @ValueSource(booleans = { false, true })
    void sculptedTerrainIsWatertightAcrossHexesCornersAndJunctions(boolean transitions) {
        BoardScenes.withTransitions(transitions, BoardSculptTest::sculptedTerrainIsWatertightAcrossHexesCornersAndJunctionsChecks);
    }

    private static void sculptedTerrainIsWatertightAcrossHexesCornersAndJunctionsChecks() {
        for (BoardScene.Surface family : BoardScene.Surface.values()) {
            BoardScene scene = scene(DRY, family);
            float floor = BoardGeometry.floor(scene);
            Map<List<Key>, Integer> edges = new HashMap<>();
            List<BoardSurface.Face> complete = new ArrayList<>();
            int triangles = 0;
            for (BoardScene.Tile tile : scene.tiles()) {
                BoardSurface surface = new BoardSurface(scene, tile);
                List<BoardSurface.Face> faces = new ArrayList<>(surface.faces);
                faces.addAll(surface.walls(scene, floor));
                complete.addAll(faces);
                for (BoardSurface.Face face : faces) {
                    if (face.finish() == BoardSurface.Finish.OUTCROP) { continue; }
                    triangles++;
                    Vector3[] p = { face.a(), face.b(), face.c() };
                    for (int i = 0; i < 3; i++) {
                        Key a = Key.of(p[i]), b = Key.of(p[(i + 1) % 3]);
                        List<Key> edge = compare(a, b) < 0 ? List.of(a, b) : List.of(b, a);
                        edges.merge(edge, 1, Integer::sum);
                    }
                }
            }
            int open = 0;
            for (var entry : edges.entrySet()) {
                if (entry.getValue() != 1) { continue; }
                // Only the board plinth's lower rim may stay open.
                boolean plinth = entry.getKey().get(0).z() == Math.round(floor * 1000)
                      && entry.getKey().get(1).z() == Math.round(floor * 1000);
                if (!plinth) { open++; }
            }
            assertTrue(triangles > 10000, "Sculpted terrain must be tessellated");
            if (family == BoardScene.Surface.CONCRETE || BoardGeometry.tuning().padding() > 0) {
                // A two-triangle cast panel can meet several collinear rock segments, and padded tops leave
                // zero-area triangles along a straight edge. Check their entire coverage rather than requiring
                // the other side to inherit every subdivision.
                BoardScenes.assertClosed(complete, floor, family.toString());
            } else {
                assertEquals(0, open, family + ": every sculpted edge must be shared by two triangles");
            }
            for (var entry : edges.entrySet()) {
                assertTrue(entry.getValue() <= 2, family + ": no edge may be shared by more than two triangles");
            }
        }
    }

    @ParameterizedTest(name = "transitions {0}")
    @ValueSource(booleans = { false, true })
    void reducedAndMixedDetailStaysWatertight(boolean transitions) {
        BoardScenes.withTransitions(transitions, BoardSculptTest::reducedAndMixedDetailStaysWatertightChecks);
    }

    private static void reducedAndMixedDetailStaysWatertightChecks() {
        int full = reducedDetailStaysWatertight(16, ignored -> TerrainLod.FULL);
        for (TerrainLod lod : List.of(TerrainLod.MEDIUM, TerrainLod.COARSE, TerrainLod.DISTANT)) {
            int reduced = reducedDetailStaysWatertight(16, ignored -> lod);
            assertTrue(reduced < full * .8f, lod + " must reduce triangles: full " + full + ", reduced " + reduced);
            reducedDetailStaysWatertight(16, at -> TerrainLod.sameChunk(at, new Coords(0, 0)) ? lod : TerrainLod.FULL);
        }
        assertEquals(full, reducedDetailStaysWatertight(200, ignored -> TerrainLod.FULL),
              "The same terrain and detail must not change when the board gets larger");
    }

    private static int reducedDetailStaysWatertight(int size, Function<Coords, TerrainLod> detail) {
        // The dry layout in the corner of a board; only its hexes and one ring are built,
        // so the open edges allowed are exactly the straight borders towards the hexes left out.
        int width = DRY[0].length() + 1, height = DRY.length + 1;
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                char cell = x < DRY[0].length() && y < DRY.length ? DRY[y].charAt(x) : '0';
                int level = cell == 'a' ? -1 : cell == 'b' ? -2 : cell - '0';
                tiles.add(new BoardScene.Tile(new Coords(x, y), level, -1, false, 0, BoardScene.Surface.SAND, null,
                      null, List.of(), List.of(), BoardLiquid.NONE, null, true));
            }
        }
        BoardScene scene = new BoardScene(0, size, size, tiles, List.of(), List.of(), -1, "", List.of());
        float floor = BoardGeometry.floor(scene);
        Map<List<Key>, Integer> edges = new HashMap<>();
        List<Vector3[]> borders = new ArrayList<>();
        int triangles = 0;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                BoardScene.Tile tile = scene.tile(new Coords(x, y));
                for (int edge = 0; edge < 6; edge++) {
                    Coords other = tile.coords().translated(BoardGeometry.edgeDirection(edge));
                    if (other.getX() >= width || other.getY() >= height) {
                        borders.add(new Vector3[] { BoardGeometry.corner(tile.coords(), 0, edge),
                              BoardGeometry.corner(tile.coords(), 0, edge + 1) });
                    }
                }
                BoardSurface surface = new BoardSurface(scene, tile, detail.apply(tile.coords()));
                List<BoardSurface.Face> faces = new ArrayList<>(surface.faces);
                faces.addAll(surface.walls(scene, floor));
                for (BoardSurface.Face face : faces) {
                    triangles++;
                    if (face.finish() == BoardSurface.Finish.OUTCROP) {
                        continue;
                    }
                    Vector3[] p = { face.a(), face.b(), face.c() };
                    for (int i = 0; i < 3; i++) {
                        Key a = Key.of(p[i]), b = Key.of(p[(i + 1) % 3]);
                        edges.merge(compare(a, b) < 0 ? List.of(a, b) : List.of(b, a), 1, Integer::sum);
                    }
                }
            }
        }
        int open = 0;
        for (var entry : edges.entrySet()) {
            assertTrue(entry.getValue() <= 2);
            if (entry.getValue() != 1) { continue; }
            boolean plinth = entry.getKey().get(0).z() == Math.round(floor * 1000)
                  && entry.getKey().get(1).z() == Math.round(floor * 1000);
            if (!plinth && !onBorder(entry.getKey().get(0), borders) || !plinth && !onBorder(entry.getKey().get(1), borders)) {
                open++;
            }
        }
        assertEquals(0, open, "Reduced sculpting still shares every edge");
        return triangles;
    }

    private static boolean onBorder(Key key, List<Vector3[]> borders) {
        for (Vector3[] border : borders) {
            float x = key.x() / 1000f, y = key.y() / 1000f;
            Vector3 a = border[0], b = border[1];
            float dx = b.x - a.x, dy = b.y - a.y;
            float t = Math.clamp(((x - a.x) * dx + (y - a.y) * dy) / (dx * dx + dy * dy), 0, 1);
            if (Math.hypot(a.x + dx * t - x, a.y + dy * t - y) < .01f) { return true; }
        }
        return false;
    }

    private static int compare(Key a, Key b) {
        return a.x() != b.x() ? Long.compare(a.x(), b.x()) : a.y() != b.y() ? Long.compare(a.y(), b.y())
              : Long.compare(a.z(), b.z());
    }

    @ParameterizedTest(name = "transitions {0}")
    @ValueSource(booleans = { false, true })
    void topsKeepGameLevelsAtAnchorsAndStayWithinBounds(boolean transitions) {
        BoardScenes.withTransitions(transitions, BoardSculptTest::topsKeepGameLevelsAtAnchorsAndStayWithinBoundsChecks);
    }

    private static void topsKeepGameLevelsAtAnchorsAndStayWithinBoundsChecks() {
        BoardScene scene = scene(DRY, BoardScene.Surface.SAND);
        for (BoardScene.Tile tile : scene.tiles()) {
            BoardSurface surface = new BoardSurface(scene, tile);
            Vector3 center = BoardGeometry.center(tile.coords(), tile.elevation());
            float anchor = surface.height(center.x, center.y);
            assertTrue(Math.abs(anchor - center.z) < BoardRelief.metres(.25f), "Unit anchor stays at its game level");
            for (BoardSurface.Face face : surface.faces) {
                Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                if (face.finish() == BoardSurface.Finish.TOP) {
                    assertTrue(normal.z > 0, "Top triangles face upward (no folds) at " + tile.coords() + ": " + face);
                }
                for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                    assertTrue(Float.isFinite(p.x) && Float.isFinite(p.y) && Float.isFinite(p.z));
                    assertTrue(p.z <= center.z + BoardRelief.headroom(tile) + .001f, "Headroom bounds every vertex");
                    float reach = (float) Math.hypot(p.x - center.x, p.y - center.y);
                    assertTrue(reach <= BoardGeometry.WIDTH / 2 + BoardRelief.overhang(), "Footprint reach is bounded");
                }
            }
        }
    }

    @ParameterizedTest(name = "transitions {0}")
    @ValueSource(booleans = { false, true })
    void wallsStayBetweenTheirLevelsAndFaceOutward(boolean transitions) {
        BoardScenes.withTransitions(transitions, BoardSculptTest::wallsStayBetweenTheirLevelsAndFaceOutwardChecks);
    }

    private static void wallsStayBetweenTheirLevelsAndFaceOutwardChecks() {
        BoardScene scene = scene(DRY, BoardScene.Surface.ROCK);
        float floor = BoardGeometry.floor(scene);
        // A transition or padding bends a wall toward the corner of a middle hex where three levels meet, so it may
        // turn further from its owner's centre there.
        float limit = BoardGeometry.tuning().stepsBetweenTops() ? -.65f : -.35f;
        for (BoardScene.Tile tile : scene.tiles()) {
            BoardSurface surface = new BoardSurface(scene, tile);
            Vector3 center = BoardGeometry.center(tile.coords(), tile.elevation());
            for (BoardSurface.Face face : surface.walls(scene, floor)) {
                float top = Math.max(face.a().z, Math.max(face.b().z, face.c().z));
                assertTrue(top <= center.z + .001f, "A cliff never rises above its owner's level");
                Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
                Vector3 middle = new Vector3(face.a()).add(face.b()).add(face.c()).scl(1 / 3f);
                Vector3 outward = new Vector3(middle.x - center.x, middle.y - center.y, 0).nor();
                assertTrue(normal.dot(outward) > limit, "Cliff faces point away from their owner");
            }
        }
    }

    @ParameterizedTest(name = "transitions {0}")
    @ValueSource(booleans = { false, true })
    void rayPickingResolvesLogicalHexesOnTopsAndCliffs(boolean transitions) {
        BoardScenes.withTransitions(transitions, BoardSculptTest::rayPickingResolvesLogicalHexesOnTopsAndCliffsChecks);
    }

    private static void rayPickingResolvesLogicalHexesOnTopsAndCliffsChecks() {
        BoardScene scene = scene(DRY, BoardScene.Surface.SAND);
        for (BoardScene.Tile tile : scene.tiles()) {
            Vector3 center = BoardGeometry.center(tile.coords(), tile.elevation());
            Ray ray = new Ray(new Vector3(center.x, center.y, 500), new Vector3(0, 0, -1));
            assertEquals(tile.coords(), BoardGeometry.pick(scene, ray), "Vertical pick at the anchor");
        }
        // Oblique rays at cliffs resolve a hex whose footprint contains the hit point or owns the top there.
        Vector3 direction = new Vector3(.45f, .35f, -.82f).nor();
        int picked = 0;
        for (BoardScene.Tile tile : scene.tiles()) {
            for (int corner = 0; corner < 6; corner++) {
                Vector3 target = BoardGeometry.corner(tile.coords(), tile.elevation(), corner)
                      .lerp(BoardGeometry.center(tile.coords(), tile.elevation()), .1f);
                Ray ray = new Ray(new Vector3(target).mulAdd(direction, -400), direction);
                BoardGeometry.Hit hit = BoardGeometry.hit(scene, ray);
                assertNotNull(hit);
                Vector3 point = ray.getEndPoint(new Vector3(), (float) Math.sqrt(hit.distance()));
                Coords owner = hit.coords();
                boolean inside = BoardGeometry.contains(owner, point.x, point.y);
                float slack = BoardRelief.overhang();
                boolean near = Math.hypot(point.x - BoardGeometry.centerX(owner), point.y - BoardGeometry.centerY(owner))
                      <= BoardGeometry.WIDTH / 2 + slack;
                assertTrue(inside || near, "A pick never resolves a distant hex");
                picked++;
            }
        }
        assertTrue(picked > 0);
    }

    @Test
    void paddedTilesStayWatertightLevelBoundedAndPickable() {
        BoardScenes.withPadding(BoardGeometry.MAX_PADDING, () -> {
            sculptedTerrainIsWatertightAcrossHexesCornersAndJunctionsChecks();
            topsKeepGameLevelsAtAnchorsAndStayWithinBoundsChecks();
            wallsStayBetweenTheirLevelsAndFaceOutwardChecks();
            rayPickingResolvesLogicalHexesOnTopsAndCliffsChecks();
        });
    }

    @Test
    void topsKeepTheirFootprintAndExactLevel() {
        for (var family : BoardScene.Surface.values()) {
            for (int level : new int[] { 0, 2, 6 }) {
                var scene = BoardScenes.meshScene(family, level);
                var surface = new BoardSurface(scene, scene.tile(BoardScenes.MESH_AT));
                var top = surface.faces.stream().filter(f -> f.finish() == BoardSurface.Finish.TOP).toList();
                Vector3 center = BoardGeometry.center(BoardScenes.MESH_AT, level);
                assertEquals(center.z, surface.height(center.x, center.y), .001f);
                float error = 0;
                double area = 0;
                for (var face : top) {
                    float cross = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).z;
                    assertTrue(cross > 0, "Tops must not fold or overlap");
                    area += cross * .5;
                    for (int a = 0; a <= 5; a++) {
                        for (int b = 0; b <= 5 - a; b++) {
                            Vector3 p = new Vector3(face.a()).scl(a / 5f).mulAdd(face.b(), b / 5f)
                                  .mulAdd(face.c(), (5 - a - b) / 5f);
                            error = Math.max(error, Math.abs(p.z - surface.relief.groundHeight(p.x, p.y)));
                        }
                    }
                }
                if (level == 0) {
                    assertEquals(BoardGeometry.width() * BoardGeometry.height() * .75, area, .05);
                }
                assertEquals(0, error, .001f, "Every top stays flat at its level: " + family);
                if (level == 0) { assertEquals(6, top.size(), "An open flat top needs six triangles"); }
                // Rounded cliff rims adaptively refine their interior normals/material shading while staying flat.
                // BoardCliffTest checks that rim shading stays local and introduces no holes at every LOD.
                int walls = surface.walls(scene, BoardGeometry.floor(scene)).size();
                assertTrue(walls <= (level <= 2 ? 900 : 5400), "Avoid dense slope/cliff grids: " + walls);
                System.out.printf("MESH %s level=%d top=%d walls=%d errorMetres=%.4f%n", family, level, top.size(),
                      walls, error / BoardRelief.metres(1));
            }
        }
    }

    @Test
    void flatTopsUseSixTrianglesAtEveryDetailIncludingBoardEdges() {
        for (var family : BoardScene.Surface.values()) {
            var scene = BoardScenes.meshScene(family, 0);
            for (var detail : TerrainLod.values()) {
                for (var at : List.of(BoardScenes.MESH_AT, new Coords(0, 0), new Coords(6, 3))) {
                    var surface = new BoardSurface(scene, scene.tile(at), detail);
                    var top = surface.groundFaces().stream().filter(f -> f.finish() == BoardSurface.Finish.TOP).toList();
                    assertEquals(6, top.size(), family + " " + detail + " " + at);
                    double area = 0;
                    for (var face : top) {
                        for (var p : List.of(face.a(), face.b(), face.c())) { assertEquals(0, p.z, .0001f); }
                        float cross = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).z;
                        assertTrue(cross > 0, "No inverted triangles");
                        area += cross * .5;
                    }
                    assertEquals(BoardGeometry.width() * BoardGeometry.height() * .75, area, .05,
                          "The entire hex remains covered");
                }
            }
        }
    }
}
