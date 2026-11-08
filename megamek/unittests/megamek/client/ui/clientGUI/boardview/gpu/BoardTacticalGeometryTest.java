/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Line2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.clientGUI.boardview.BoardRangeBorder;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.client.ui.clientGUI.boardview.HexDrawUtilities;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class BoardTacticalGeometryTest {
    /**
     * A range zone's run across a hex starts and ends on corners its neighbours share, and its band ends on the outline
     * where a side band of the same width meets that side, so the zone's bands join across hexes.
     */
    @Test
    void hexCrossingsRunBetweenCornersAndTheirBandsEndWhereSideBandsDo() {
        double width = 10, alongSide = width * 2 / Math.sqrt(3);
        List<Point2D> corners = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            Vector3 corner = BoardGeometry.modelCorner(k);
            corners.add(new Point2D.Double(corner.x, -corner.y));
        }
        for (int sides = 2; sides <= 5; sides++) {
            for (int turn = 0; turn < 6; turn++) {
                var crossing = BoardTacticalGeometry.crossing(sides, turn, width);
                List<Point2D> line = points(crossing.line()), band = points(crossing.band());
                assertEquals(2 * line.size(), band.size());
                for (Point2D end : List.of(line.getFirst(), line.getLast())) {
                    assertTrue(corners.stream().anyMatch(corner -> corner.distance(end) < 1e-6), sides + "/" + turn);
                }
                for (Point2D point : band) {
                    assertTrue(BoardTacticalGeometry.insetHexagon(-1e-4).contains(point),
                          sides + "/" + turn + ": " + point);
                }
                for (Point2D[] end : List.of(new Point2D[] { line.getLast(), band.get(line.size()) },
                      new Point2D[] { line.getFirst(), band.getLast() })) {
                    double outline = Double.MAX_VALUE;
                    for (int k = 0; k < 6; k++) {
                        Point2D a = corners.get(k), b = corners.get((k + 1) % 6);
                        outline = Math.min(outline, Line2D.ptSegDist(a.getX(), a.getY(), b.getX(), b.getY(),
                              end[1].getX(), end[1].getY()));
                    }
                    assertEquals(0, outline, 1e-4, "The band ends on the outline: " + sides + "/" + turn);
                    assertEquals(alongSide, end[0].distance(end[1]), 1e-3,
                          "As far along it as a side band: " + sides + "/" + turn);
                }
            }
        }
    }

    private static List<Point2D> points(Shape shape) {
        List<Point2D> points = new ArrayList<>();
        double[] xy = new double[6];
        for (PathIterator path = shape.getPathIterator(null); !path.isDone(); path.next()) {
            if (path.currentSegment(xy) != PathIterator.SEG_CLOSE) { points.add(new Point2D.Double(xy[0], xy[1])); }
        }
        return points;
    }

    @Test
    void mines0708RoadRampDoesNotRaiseTheFlatMarkerPlane() throws Exception {
        BoardScene scene = BoardScenes.minesScene();
        Coords coords = new Coords(6, 7);
        var tile = scene.tile(coords);
        assertEquals(-1, tile.elevation());
        assertEquals(0, scene.tile(new Coords(7, 7)).elevation());
        var fill = hexBorder(coords, true);
        float expected = -BoardGeometry.level() + BoardTacticalGeometry.HEX_PLANE_CLEARANCE;
        for (var lod : TerrainLod.values()) {
            var surface = BoardTacticalGeometry.Surface.of(new BoardSurface(scene, tile, lod), scene, BoardGeometry.floor(scene));
            assertTrue(surface.highestTop() > -BoardGeometry.level() + BoardGeometry.level() * .25f,
                  "The actual road ramp must climb above the hex's own level at " + lod);
            List<BoardTacticalGeometry.Triangle> triangles = new ArrayList<>();
            BoardTacticalGeometry.drape(scene, fill, 0, triangles::add, owner -> surface,
                  new BoardTacticalGeometry.Clipper());
            assertFalse(triangles.isEmpty());
            for (var triangle : triangles) {
                for (var vertex : List.of(triangle.a(), triangle.b(), triangle.c())) {
                    assertEquals(expected, vertex.z, .00001f,
                          "0708 must keep its level -1 marker plane despite the road to level 0 at " + lod);
                }
            }
        }
    }

    @Test
    void floatingBordersKeepTheOwnerElevationWithoutQueryingTerrain() {
        var fill = hexBorder(new Coords(1, 1), true);
        var flat = BoardTacticalGeometry.flat(fill);
        assertFalse(flat.isEmpty());
        for (int level : new int[] { -2, 0, 3 }) {
            for (int depth : new int[] { -1, 5 }) {
                for (boolean frozen : new boolean[] { false, true }) {
                    BoardScene scene = borderScene(fill, level, depth, frozen);
                    assertEquals(new Coords(1, 1), BoardTacticalGeometry.borderCoords(scene, fill.border()));
                    for (int layer : new int[] { 0, 10000, 10001 }) {
                        List<BoardTacticalGeometry.Triangle> actual = new ArrayList<>();
                        BoardTacticalGeometry.drape(scene, fill, layer, actual::add, coords -> {
                            throw new AssertionError("A flat annotation must not query terrain at " + coords);
                        }, new BoardTacticalGeometry.Clipper());
                        assertEquals(flat.size(), actual.size());
                        float height = level * BoardGeometry.LEVEL + BoardTacticalGeometry.HEX_PLANE_CLEARANCE
                              - (depth >= 0 && !frozen ? BoardGeometry.HEX_SCALE : 0);
                        for (int index = 0; index < flat.size(); index++) {
                            var a = flat.get(index);
                            var b = actual.get(index);
                            assertEquals(a.argb(), b.argb());
                            List<Vector3> source = List.of(a.a(), a.b(), a.c());
                            List<Vector3> result = List.of(b.a(), b.b(), b.c());
                            for (int vertex = 0; vertex < 3; vertex++) {
                                assertEquals(source.get(vertex).x * BoardGeometry.HEX_SCALE, result.get(vertex).x);
                                assertEquals(-source.get(vertex).y * BoardGeometry.HEX_SCALE, result.get(vertex).y);
                                assertEquals(height, result.get(vertex).z, .00001f,
                                      "Keep the owner's level; ignore beds, slopes, neighbors and painter order");
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void ordinaryBordersStillDrapeExactlyAndMissingOwnersDoNotCreateFloatingGeometry() {
        var ordinary = hexBorder(new Coords(1, 1), false);
        var generic = new BoardTactical.Fill(ordinary.contours(), ordinary.winding(), ordinary.argb(), ordinary.playback());
        BoardScene scene = borderScene(ordinary, 2, 5, false);
        var surfaces = BoardTacticalGeometry.surfaces(scene);
        List<BoardTacticalGeometry.Triangle> expected = new ArrayList<>(), actual = new ArrayList<>();
        BoardTacticalGeometry.drape(scene, generic, 7, expected::add, surfaces, new BoardTacticalGeometry.Clipper());
        int[] queries = { 0 };
        BoardTacticalGeometry.drape(scene, ordinary, 7, actual::add, coords -> {
            queries[0]++;
            return surfaces.apply(coords);
        }, new BoardTacticalGeometry.Clipper());
        assertTrue(queries[0] > 0);
        assertFalse(expected.isEmpty());
        assertEquals(expected, actual, "A nonfloating hint must leave the existing drape path unchanged");

        actual.clear();
        assertFalse(BoardTacticalGeometry.floating(scene, generic, actual::add));
        assertFalse(BoardTacticalGeometry.floating(scene, ordinary, actual::add));
        var outside = hexBorder(new Coords(3, 1), true);
        assertNull(BoardTacticalGeometry.borderCoords(scene, outside.border()));
        assertFalse(BoardTacticalGeometry.floating(scene, outside, actual::add));
        assertTrue(actual.isEmpty());
    }

    private static BoardTactical.Fill hexBorder(Coords coords, boolean floating) {
        var graphics = new BoardTacticalGraphics();
        try {
            var hex = floating ? BoardTacticalGraphics.onHexPlane(graphics, coords)
                  : BoardTacticalGraphics.at(graphics, coords);
            hex.setColor(new Color(45, 160, 215, 109));
            hex.fillHexBorder(1.5, 2.25);
            return graphics.snapshot().fills().getFirst();
        } finally {
            graphics.dispose();
        }
    }

    /** An upright boundary in the graphics' colour, its outline in the graphics' stroke. */
    private static void wall(BoardTacticalGraphics graphics, Shape line, Shape footprint, Coords coords, float height,
          Color outline) {
        var stroke = (BasicStroke) graphics.getStroke();
        graphics.wall(line, footprint, coords, new BoardRangeBorder(height, graphics.getColor().getRGB(),
              outline == null ? null : new BoardTactical.Outline(outline.getRGB(), stroke)));
    }

    private static BoardScene borderScene(BoardTactical.Fill fill, int level, int depth, boolean frozen) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 2; y++) {
                boolean owner = x == 1 && y == 1;
                tiles.add(new BoardScene.Tile(new Coords(x, y), owner ? level : level + 7, owner ? depth : -1,
                      owner && frozen, 0, BoardScene.Surface.GRASS, null, List.of(), List.of()));
            }
        }
        return new BoardScene(0, 2, 2, tiles, List.of(), List.of(), -1, "", List.of(), null,
              List.of(), List.of(), List.of(), new BoardTactical(List.of(fill), List.of()));
    }

    @Test
    void originalWhiteStrokeFollowsTheTopOfTheTranslucentWall() {
        var graphics = new BoardTacticalGraphics();
        try {
            Coords coords = new Coords(0, 0);
            graphics.setColor(new Color(35, 150, 200, 128));
            graphics.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10,
                  new float[] { 2, 2 }, 0));
            wall(graphics, HexDrawUtilities.getHexCrossLine01(4, 10), HexDrawUtilities.getHexCrossArea01(4, 10),
                  coords, 0.5f, Color.WHITE);
            var tile = new BoardScene.Tile(coords, 2, 4, false, 0,
                  BoardScene.Surface.GRASS, null, List.of(), List.of());
            var scene = new BoardScene(0, 1, 1, List.of(tile), List.of(), List.of(), -1, "", List.of(), null,
                  List.of(), List.of(), List.of(), graphics.snapshot());
            List<BoardTacticalGeometry.Triangle> triangles = new ArrayList<>();
            BoardTacticalGeometry.walls(scene, false, triangles::add, (wall, triangle) -> triangles.add(triangle));
            var face = triangles.stream().filter(t -> (t.argb() >>> 24) == 128).toList();
            var outline = triangles.stream().filter(t -> t.argb() == Color.WHITE.getRGB()).toList();
            assertEquals(2, face.size());
            assertFalse(outline.isEmpty());
            float top = Math.max(face.getFirst().a().z, Math.max(face.getFirst().b().z, face.getFirst().c().z));
            for (var triangle : outline) {
                for (Vector3 point : List.of(triangle.a(), triangle.b(), triangle.c())) {
                    assertEquals(top, point.z, 0.001f);
                }
            }
            double area = outline.stream().mapToDouble(t -> new Vector3(t.b()).sub(t.a())
                  .crs(new Vector3(t.c()).sub(t.a())).len() / 2).sum();
            assertEquals(72 * 1.4 * BoardGeometry.HEX_SCALE * BoardGeometry.HEX_SCALE, area, 0.01,
                  "The original stroke width is retained; the scrolling texture supplies its dash gaps");
        } finally {
            graphics.dispose();
        }
    }

    @Test
    void uprightBoundaryJoinsAcrossElevationChangesWithoutGaps() {
        var graphics = new BoardTacticalGraphics();
        try {
            List<BoardScene.Tile> tiles = new ArrayList<>();
            int[] levels = { 0, 3, 3, -2 };
            for (int y = 0; y < levels.length; y++) {
                Coords coords = new Coords(0, y);
                tiles.add(new BoardScene.Tile(coords, levels[y], y == 3 ? 5 : -1, false, 0,
                      BoardScene.Surface.GRASS, null, List.of(), List.of()));
                var local = (BoardTacticalGraphics) graphics.create();
                try {
                    local.translate(0, y * BoardGeometry.TILE_HEIGHT);
                    wall(local, HexDrawUtilities.getHexCrossLine01(4, 10), HexDrawUtilities.getHexCrossArea01(4, 10),
                          coords, 0.5f, null);
                } finally {
                    local.dispose();
                }
            }
            var scene = new BoardScene(0, 1, levels.length, tiles, List.of(), List.of(), -1, "", List.of(), null,
                  List.of(), List.of(), List.of(), graphics.snapshot());
            List<BoardTacticalGeometry.Triangle> triangles = new ArrayList<>();
            BoardTacticalGeometry.walls(scene, false, triangles::add, (wall, triangle) -> triangles.add(triangle));
            assertEquals(levels.length * 2, triangles.size());
            for (int y = 1; y < levels.length; y++) {
                float seam = -y * BoardGeometry.HEIGHT;
                var previous = triangles.subList((y - 1) * 2, y * 2).stream()
                      .flatMap(t -> List.of(t.a(), t.b(), t.c()).stream())
                      .filter(p -> Math.abs(p.y - seam) < 0.001f).map(p -> p.z).distinct().sorted().toList();
                var next = triangles.subList(y * 2, (y + 1) * 2).stream()
                      .flatMap(t -> List.of(t.a(), t.b(), t.c()).stream())
                      .filter(p -> Math.abs(p.y - seam) < 0.001f).map(p -> p.z).distinct().sorted().toList();
                assertEquals(2, previous.size());
                assertEquals(previous, next, "Both panels must meet along the same vertical span");
                assertTrue(previous.getFirst() >= Math.min(levels[y - 1], levels[y]) * BoardGeometry.LEVEL);
                assertTrue(previous.getLast() >= (Math.max(levels[y - 1], levels[y]) + 0.5f) * BoardGeometry.LEVEL);
            }
        } finally {
            graphics.dispose();
        }
    }

    @Test
    void flattenedBandAndOutlineStayOnTheSurfaceOverDeepWaterAndIce() {
        var graphics = new BoardTacticalGraphics();
        try {
            Coords coords = new Coords(0, 0);
            graphics.setColor(new Color(35, 150, 200, 128));
            graphics.setStroke(new BasicStroke(1.4f));
            wall(graphics, HexDrawUtilities.getHexCrossLine01(4, 10), HexDrawUtilities.getHexCrossArea01(4, 10),
                  coords, 0.5f, Color.WHITE);
            for (int level : new int[] { -2, 0, 3 }) {
                for (boolean frozen : new boolean[] { false, true }) {
                    var tile = new BoardScene.Tile(coords, level, 5, frozen, 0,
                          BoardScene.Surface.GRASS, null, List.of(), List.of());
                    var scene = new BoardScene(0, 1, 1, List.of(tile), List.of(), List.of(), -1, "", List.of(), null,
                          List.of(), List.of(), List.of(), graphics.snapshot());
                    List<BoardTacticalGeometry.Triangle> faces = new ArrayList<>(), outlines = new ArrayList<>();
                    BoardTacticalGeometry.walls(scene, true, faces::add, (wall, triangle) -> outlines.add(triangle));
                    assertFalse(faces.isEmpty());
                    assertFalse(outlines.isEmpty());
                    var triangles = new ArrayList<>(faces);
                    triangles.addAll(outlines);
                    for (var triangle : triangles) {
                        for (Vector3 point : List.of(triangle.a(), triangle.b(), triangle.c())) {
                            assertTrue(point.z >= BoardGeometry.surfaceZ(tile), "Stay above water, never on the lakebed");
                            assertTrue(point.z < level * BoardGeometry.LEVEL + BoardGeometry.HEX_SCALE,
                                  "The overhead band must lie on the surface rather than retain the wall height");
                            assertTrue(BoardGeometry.contains(coords, point.x, point.y));
                        }
                    }
                }
            }
        } finally {
            graphics.dispose();
        }
    }

    @Test
    void uprightBoundaryStaysHalfALevelAboveTheSurfaceRegardlessOfDepthOrIce() {
        var graphics = new BoardTacticalGraphics();
        try {
            Coords coords = new Coords(0, 0);
            graphics.setColor(new Color(255, 204, 255, 96));
            wall(graphics, HexDrawUtilities.getHexCrossLine01(4, 10), HexDrawUtilities.getHexCrossArea01(4, 10),
                  coords, 0.5f, null);
            for (int level : new int[] { -2, 0, 3 }) {
                for (int depth : new int[] { -1, 0, 5 }) {
                    for (boolean frozen : new boolean[] { false, true }) {
                        var tile = new BoardScene.Tile(coords, level, depth, frozen, 0,
                              BoardScene.Surface.GRASS, null, List.of(), List.of());
                        var scene = new BoardScene(0, 1, 1, List.of(tile), List.of(), List.of(), -1, "", List.of(), null,
                              List.of(), List.of(), List.of(), graphics.snapshot());
                        List<BoardTacticalGeometry.Triangle> triangles = new ArrayList<>();
                        BoardTacticalGeometry.walls(scene, false, triangles::add,
                              (wall, triangle) -> triangles.add(triangle));
                        assertEquals(2, triangles.size());
                        var points = triangles.stream().flatMap(t -> List.of(t.a(), t.b(), t.c()).stream()).toList();
                        double bottom = points.stream().mapToDouble(p -> p.z).min().orElseThrow();
                        double top = points.stream().mapToDouble(p -> p.z).max().orElseThrow();
                        assertTrue(bottom >= level * BoardGeometry.LEVEL);
                        assertTrue(bottom < level * BoardGeometry.LEVEL + BoardGeometry.HEX_SCALE);
                        assertEquals(0.5f * BoardGeometry.LEVEL, top - bottom, 0.001);
                        for (Vector3 point : points) {
                            assertEquals(21 * BoardGeometry.HEX_SCALE, point.x, 0.001f,
                                  "The vertical wall keeps the straight path across the hex");
                        }
                        for (var triangle : triangles) {
                            assertTrue(new Vector3(triangle.b()).sub(triangle.a())
                                  .crs(new Vector3(triangle.c()).sub(triangle.a())).len2() > 0);
                            assertEquals(96, triangle.argb() >>> 24);
                        }
                    }
                }
            }
        } finally {
            graphics.dispose();
        }
    }

    @Test
    void tessellationPreservesHolesConcavityAndTranslucency() {
        var graphics = new BoardTacticalGraphics();
        try {
            Area ring = new Area(new Rectangle2D.Float(0, 0, 80, 60));
            ring.subtract(new Area(new Rectangle2D.Float(10, 10, 60, 40)));
            graphics.setColor(new Color(20, 120, 220, 80));
            graphics.fill(ring);
            var fill = graphics.snapshot().fills().getFirst();
            var triangles = BoardTacticalGeometry.flat(fill);
            double area = triangles.stream().mapToDouble(t -> Math.abs((t.b().x - t.a().x) * (t.c().y - t.a().y)
                  - (t.b().y - t.a().y) * (t.c().x - t.a().x)) / 2).sum();
            assertEquals(2400, area, 0.001);
            for (var triangle : triangles) {
                assertEquals(80, triangle.argb() >>> 24);
                assertFalse(Intersector.isPointInTriangle(new Vector2(40, 30),
                      new Vector2(triangle.a().x, triangle.a().y), new Vector2(triangle.b().x, triangle.b().y),
                      new Vector2(triangle.c().x, triangle.c().y)), "The empty centre must remain empty");
            }
            graphics.setStroke(new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10,
                  new float[] { 5, 3 }, 0));
            graphics.drawLine(0, 70, 80, 70);
            assertTrue(graphics.snapshot().fills().getLast().contours().size() > 1, "Dash gaps stay separate");
        } finally {
            graphics.dispose();
        }
    }

    @Test
    void geometryFollowsRaisedAndLoweredHexesAndWaterWithoutCrossingOutsideTheBoard() {
        var graphics = new BoardTacticalGraphics();
        try {
            graphics.setColor(Color.CYAN);
            graphics.fillRect(-20, -20, 300, 150);
            List<BoardScene.Tile> tiles = new ArrayList<>();
            for (int x = 0; x < 3; x++) {
                tiles.add(new BoardScene.Tile(new Coords(x, 0), x - 1, x == 0 ? 1 : -1, false, 0,
                      BoardScene.Surface.GRASS, null, List.of(), List.of()));
            }
            var scene = new BoardScene(0, 3, 1, tiles, List.of(), List.of(), -1, "", List.of(), null,
                  List.of(), List.of(), List.of(), graphics.snapshot());
            List<BoardTacticalGeometry.Triangle> triangles = new ArrayList<>();
            List<BoardTacticalGeometry.Triangle> captured = new ArrayList<>();
            BoardTacticalGeometry.drape(scene, triangle -> {
                triangles.add(triangle);
                captured.add(new BoardTacticalGeometry.Triangle(new Vector3(triangle.a()), new Vector3(triangle.b()),
                      new Vector3(triangle.c()), triangle.argb()));
            });
            assertFalse(triangles.isEmpty());
            assertEquals(captured, triangles, "Later surface clips must not overwrite earlier emitted vertices");
            assertTrue(triangles.stream().flatMap(t -> List.of(t.a(), t.b(), t.c()).stream())
                  .anyMatch(p -> p.z > BoardGeometry.LEVEL));
            assertTrue(triangles.stream().flatMap(t -> List.of(t.a(), t.b(), t.c()).stream())
                  .anyMatch(p -> p.z < -BoardGeometry.LEVEL));
            for (var triangle : triangles) {
                Vector3 centre = new Vector3(triangle.a()).add(triangle.b()).add(triangle.c()).scl(1f / 3);
                assertTrue(tiles.stream().anyMatch(tile -> BoardGeometry.contains(tile.coords(), centre.x, centre.y)));
                assertTrue(Float.isFinite(centre.z));
            }
        } finally {
            graphics.dispose();
        }
    }
}
