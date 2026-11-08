/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

/** Rim prominence by drop, concrete slabs, tree pits, special artwork, joined landforms and compressed elevations. */
class BoardReliefTest {

    /** Largest distance the cliff's upper tenth stands out beyond the logical edge. */
    private static float rimReach(int level) {
        BoardScene scene = BoardScenes.reliefScene(level, true, BoardScene.Surface.SAND);
        BoardSurface surface = new BoardSurface(scene, scene.tile(BoardScenes.RELIEF_CENTER));
        Vector3 center = BoardGeometry.center(BoardScenes.RELIEF_CENTER, level);
        float apothem = BoardGeometry.HEIGHT / 2, reach = 0;
        for (BoardSurface.Face face : surface.walls(scene, BoardGeometry.floor(scene))) {
            for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                if (p.z < center.z - .1f * BoardGeometry.LEVEL * level) { continue; }
                float radial = (float) Math.hypot(p.x - center.x, p.y - center.y);
                // Compare against the hexagon's own radius in that direction.
                float angle = (float) Math.atan2(p.y - center.y, p.x - center.x);
                float sector = (float) (Math.PI / 3);
                float local = (float) Math.abs(((angle % sector) + sector) % sector - sector / 2);
                float boundary = apothem / (float) Math.cos(local);
                reach = Math.max(reach, radial - Math.min(boundary, BoardGeometry.WIDTH / 2));
            }
        }
        return reach;
    }

    @Test
    void deepDropsCarryMoreProminentRimFormationsThanShallowOnes() {
        // Without hex transitions, whose room sets every rim back from its edge.
        BoardScenes.withTransitions(false, () -> {
            float shallow = Math.max(rimReach(1), rimReach(2));
            float deep = Math.min(rimReach(3), rimReach(4));
            assertTrue(deep > shallow * 1.2f, "Caprock reach: shallow " + shallow + ", deep " + deep);
            assertTrue(shallow > 0, "Even shallow drops have a readable lip");
        });
    }

    @Test
    void specialArtworkKeepsALevelTopInsideItsSculptedOutline() {
        BoardScene scene = BoardScenes.reliefScene(2, false, BoardScene.Surface.GRASS);
        BoardSurface surface = new BoardSurface(scene, scene.tile(BoardScenes.RELIEF_CENTER));
        for (BoardSurface.Face face : surface.faces) {
            for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                assertEquals(2 * BoardGeometry.LEVEL, p.z, .001f, "Special ground art stays on its game level");
            }
        }
        assertTrue(surface.relief.sculpted(), "Its cliffs are still sculpted");
    }

    @Test
    void joinedLandformsShareHeightsAcrossFamiliesAndKeepAnchorsLevel() {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                tiles.add(new BoardScene.Tile(new Coords(x, y), 1, -1, false, 0,
                      x < 2 ? BoardScene.Surface.GRASS : BoardScene.Surface.SAND, null, null,
                      List.of(), List.of(), BoardLiquid.NONE, null, true));
            }
        }
        BoardScene scene = new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
        float maximum = Float.NEGATIVE_INFINITY, minimum = Float.POSITIVE_INFINITY;
        for (Coords coords : List.of(new Coords(1, 2), new Coords(2, 2))) {
            BoardSurface surface = new BoardSurface(scene, scene.tile(coords));
            Vector3 center = BoardGeometry.center(coords, 1);
            assertEquals(center.z, surface.height(center.x, center.y), .003f, "Unit anchors stay level");
            for (int direction = 0; direction < 6; direction++) {
                Coords other = coords.translated(direction);
                BoardSurface neighbor = new BoardSurface(scene, scene.tile(other));
                int edge = Math.floorMod(1 - direction, 6);
                for (int step = 1; step < TerrainLod.FULL.steps; step++) {
                    Vector3 p = BoardGeometry.corner(coords, 1, edge)
                          .lerp(BoardGeometry.corner(coords, 1, edge + 1), step / (float) TerrainLod.FULL.steps);
                    // Nudge inside each hex: both sampled faces must agree on either side of the shared edge.
                    Vector3 inside = new Vector3(p).lerp(center, .002f);
                    Vector3 outside = new Vector3(p).lerp(BoardGeometry.center(other, 1), .002f);
                    assertEquals(surface.height(inside.x, inside.y), neighbor.height(outside.x, outside.y), .05f,
                          "Joined landforms have no seam");
                    maximum = Math.max(maximum, surface.height(inside.x, inside.y));
                    minimum = Math.min(minimum, surface.height(inside.x, inside.y));
                }
            }
        }
        assertEquals(BoardGeometry.level(), minimum, .001f, "Level terrain has no geometric height noise");
        assertEquals(minimum, maximum, .001f, "Joined families share one flat plane");
    }

    @Test
    void stepsAndPavingReachingTheBoardEdgeKeepItsHexOutline() {
        // A hill on the board's top edge, whose steps down to the hexes beside it run into the board's cut.
        Coords hill = new Coords(2, 0), corner = new Coords(0, 0);
        assertKeepsHexOutline(edgeBoard(coords -> coords.equals(hill) ? 1 : 0, coords -> false));
        // Paving in the board's corner below a plateau: its fitted outline ends on the board's edge.
        assertKeepsHexOutline(edgeBoard(coords -> coords.equals(corner) ? 0 : 1, corner::equals));
    }

    private static BoardScene edgeBoard(ToIntFunction<Coords> level, Predicate<Coords> paved) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 3; y++) {
                Coords coords = new Coords(x, y);
                tiles.add(new BoardScene.Tile(coords, level.applyAsInt(coords), -1, false, 0,
                      paved.test(coords) ? BoardScene.Surface.CONCRETE : BoardScene.Surface.GRASS, null, null, List.of(), List.of(), BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, 5, 3, tiles, List.of(), List.of(), -1, "", List.of());
    }

    /** Nothing reaches past the board's outline, and its cut still meets every lattice corner of that outline. */
    private static void assertKeepsHexOutline(BoardScene scene) {
        BoardScenes.withTransitions(true, () -> {
            List<Vector3> points = new ArrayList<>();
            for (BoardScene.Tile tile : scene.tiles()) {
                BoardSurface surface = new BoardSurface(scene, tile);
                List<BoardSurface.Face> faces = new ArrayList<>(surface.faces);
                faces.addAll(surface.walls(scene, BoardGeometry.floor(scene)));
                for (BoardSurface.Face face : faces) {
                    Vector3 middle = new Vector3(face.a()).add(face.b()).add(face.c()).scl(1 / 3f);
                    points.addAll(List.of(face.a(), face.b(), face.c(), middle));
                }
            }
            float tolerance = 1e-4f * BoardGeometry.width();
            for (Vector3 p : points) {
                float beyond = Float.MAX_VALUE;
                for (BoardScene.Tile tile : scene.tiles()) {
                    float outside = -Float.MAX_VALUE;
                    for (int edge = 0; edge < 6; edge++) {
                        Vector3 a = BoardGeometry.corner(tile.coords(), 0, edge);
                        Vector3 b = BoardGeometry.corner(tile.coords(), 0, edge + 1);
                        float length = (float) Math.hypot(b.x - a.x, b.y - a.y);
                        // Corners run counterclockwise, so the outside lies right of each edge.
                        outside = Math.max(outside, ((p.x - a.x) * (b.y - a.y) - (p.y - a.y) * (b.x - a.x)) / length);
                    }
                    beyond = Math.min(beyond, outside);
                }
                assertTrue(beyond < tolerance, "Nothing reaches past the board's outline, at " + p);
            }
            for (BoardScene.Tile tile : scene.tiles()) {
                for (int k = 0; k < 6; k++) {
                    if (scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(k))) != null
                          && scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(k + 5))) != null) { continue; }
                    Vector3 lattice = BoardGeometry.corner(tile.coords(), 0, k);
                    assertTrue(points.stream().anyMatch(p -> Math.hypot(p.x - lattice.x, p.y - lattice.y) < tolerance),
                          "The board's cut keeps its corner at " + lattice);
                }
            }
        });
    }

    @Test
    void compressedElevationSettingsKeepSculptedGeometryAboveThePickingFloor() {
        BoardGeometry.Tuning previous = BoardGeometry.tuning();
        try {
            BoardGeometry.tune(new BoardGeometry.Tuning(1, previous.unitScale(), previous.unitHeightScale(), 1,
                  previous.gridShade(), previous.multiHexUnitScale()));
            BoardScene scene = BoardScenes.reliefScene(3, true, BoardScene.Surface.ROCK);
            float floor = BoardGeometry.floor(scene);
            for (BoardScene.Tile tile : scene.tiles()) {
                BoardSurface surface = new BoardSurface(scene, tile);
                List<BoardSurface.Face> faces = new ArrayList<>(surface.faces);
                faces.addAll(surface.walls(scene, floor));
                for (BoardSurface.Face face : faces) {
                    assertTrue(Math.min(face.a().z, Math.min(face.b().z, face.c().z)) >= floor - .001f,
                          "Sculpted geometry stays inside the board and ray bounds at minimum level height");
                }
            }
            assertNotNull(BoardGeometry.pick(scene, new com.badlogic.gdx.math.collision.Ray(
                  new Vector3(BoardGeometry.center(BoardScenes.RELIEF_CENTER, 3)).add(0, 0, 100), new Vector3(0, 0, -1))));
        } finally { BoardGeometry.tune(previous); }
    }

    @Test
    void mixedLiquidFieldsStayExactAcrossQueryOrderOwnersAndBoardEdges() {
        try (var ignored = TerrainSettings.use(TerrainSettings.capture())) {
            BoardScene scene = scene(false);
            List<Vector3> points = points();
            int[][] expected = new int[points.size()][2];
            int differentLiquids = 0;
            Coords anchor = new Coords(3, 3);
            for (int i = 0; i < points.size(); i++) {
                Vector3 p = points.get(i);
                for (int liquid = 0; liquid < 2; liquid++) {
                    // Each reference begins cold and queries only one liquid.
                    expected[i][liquid] = bits(relief(scene, anchor).shore(p.x, p.y, liquid == 1));
                }
                if (expected[i][0] != expected[i][1]) { differentLiquids++; }
            }
            assertTrue(differentLiquids > 0, "The two liquid fields must actually differ in this fixture");
            for (Coords owner : List.of(anchor, anchor.translated(2), new Coords(0, 0), new Coords(8, 8))) {
                var relief = relief(scene, owner);
                for (int pass = 0; pass < 3; pass++) {
                    for (int at = 0; at < points.size(); at++) {
                        int i = pass == 1 ? points.size() - 1 - at : at;
                        Vector3 p = points.get(i);
                        for (int order = 0; order < 2; order++) {
                            int liquid = (i + pass + order) & 1;
                            assertEquals(expected[i][liquid], bits(relief.shore(p.x, p.y, liquid == 1)),
                                  "Shared shore at " + p + ", owner=" + owner + ", molten=" + (liquid == 1)
                                        + ", pass=" + pass);
                        }
                    }
                }
            }
        }
    }

    @Test
    void newSceneDoesNotReuseThePreviousLiquidClassification() {
        try (var ignored = TerrainSettings.use(TerrainSettings.capture())) {
            Coords at = new Coords(3, 3);
            var before = relief(scene(false), at);
            var after = relief(scene(true), at);
            int changed = 0;
            for (Vector3 p : points()) {
                int old = bits(before.shore(p.x, p.y, true));
                int next = bits(after.shore(p.x, p.y, true));
                assertEquals(next, bits(after.shore(p.x, p.y, true)), "New scene remains stable after warming");
                assertEquals(old, bits(before.shore(p.x, p.y, true)), "Old captured scene retains its own field");
                if (old != next) { changed++; }
            }
            assertTrue(changed > 0, "Changing liquid types must change the molten shore field");
        }
    }

    private static int bits(float value) {
        assertTrue(Float.isFinite(value), "Shore queries must stay finite");
        return Float.floatToRawIntBits(value);
    }

    private static BoardRelief relief(BoardScene scene, Coords at) {
        return new BoardRelief(scene, scene.tile(at), BoardSurface.ramps(scene, scene.tile(at)));
    }

    private static List<Vector3> points() {
        List<Vector3> result = new ArrayList<>();
        // Includes off-board clamping and points beyond any owner's six-cell memo window.
        for (int x : new int[] { -8, -1, 0, 3, 4, 8, 15 }) {
            for (int y : new int[] { -8, 0, 3, 4, 8, 15 }) {
                result.add(BoardGeometry.center(new Coords(x, y), 0).add(.25f, -.75f, 0));
            }
        }
        for (Coords at : List.of(new Coords(0, 0), new Coords(3, 3), new Coords(4, 3))) {
            for (int edge = 0; edge < 6; edge++) {
                result.add(BoardGeometry.corner(at, 0, edge));
                result.add(BoardGeometry.corner(at, 0, edge).lerp(BoardGeometry.corner(at, 0, edge + 1), .5f));
            }
        }
        return result;
    }

    private static BoardScene scene(boolean swapLiquids) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 9; x++) {
            for (int y = 0; y < 9; y++) {
                boolean pool = x >= 1 && x <= 7 && y >= 2 && y <= 6;
                boolean molten = (x >= 4) != swapLiquids;
                BoardLiquid liquid = !pool ? BoardLiquid.NONE
                      : molten ? new BoardLiquid(BoardLiquid.Kind.MAGMA, "", 0) : BoardLiquid.WATER;
                BoardScene.Surface family = x == 4 && y == 1 ? BoardScene.Surface.CONCRETE : BoardScene.Surface.SAND;
                tiles.add(new BoardScene.Tile(new Coords(x, y), pool ? 0 : 2, pool ? 1 : -1, false, 0, family, null,
                      null, List.of(), List.of(), liquid, null, !(x == 0 && y == 0)));
            }
        }
        return new BoardScene(0, 9, 9, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
