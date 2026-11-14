/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Point;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.clientGUI.boardview.BoardRangeBorder;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class BoardDeploymentGeometryTest {
    @Test
    void capturedColorsComposeInPainterOrderAndOtherOverlaysStayOutsideTheZone() {
        Coords a = new Coords(2, 2), b = new Coords(3, 2);
        var yellow = fill(a, Color.YELLOW.getRGB());
        var warning = fill(a, 0x80FF0000);
        var ordinary = new BoardTactical.Fill(yellow.contours(), yellow.winding(), yellow.argb(), yellow.playback(),
              new BoardTactical.HexBorder(yellow.border().anchor(), 0, 2, 1), yellow.planeAnchor());
        var generic = new BoardTactical.Fill(yellow.contours(), yellow.winding(), Color.WHITE.getRGB());
        BoardScene scene = scene(9, 9, List.of(fill(b, 0x800000FF), yellow, ordinary, generic,
              fill(new Coords(20, 20), Color.YELLOW.getRGB()), warning));
        var zones = BoardDeploymentGeometry.zoneFills(scene);
        assertTrue(BoardDeploymentGeometry.isZone(yellow));
        assertFalse(BoardDeploymentGeometry.isZone(ordinary));
        assertFalse(BoardDeploymentGeometry.isZone(generic));
        assertEquals(List.of(b, a), new ArrayList<>(zones.keySet()));
        assertEquals(0xFFFF7F00, zones.get(a).argb(), "A translucent red warning remains orange over yellow");
        assertEquals(0x800000FF, zones.get(b).argb());
        assertEquals(warning.playback(), zones.get(a).playback());

        var translucent = BoardDeploymentGeometry.zoneFills(scene(9, 9,
              List.of(fill(a, 0x80FF0000), fill(a, 0x800000FF))));
        assertEquals(0xC05500AA, translucent.get(a).argb(), "Straight-alpha source-over preserves both warning colors");
        var opaque = fill(a, Color.CYAN.getRGB());
        assertSame(opaque, BoardDeploymentGeometry.zoneFills(scene(9, 9, List.of(yellow, opaque))).get(a));

        BoardTactical hidden = new BoardTactical(List.of(generic), List.of()).duringPlayback(scene.tactical(), true);
        assertTrue(BoardDeploymentGeometry.zoneFills(scene(9, 9, hidden.fills())).isEmpty());
        assertEquals(List.of(generic), hidden.fills(), "Live unrelated artwork remains visible while deployment is hidden");
    }

    @Test
    void equalNeighborsLoseTheirSharedEdgeAndDifferentColorsKeepBothSides() {
        Coords a = new Coords(3, 3), b = a.translated(1);
        BoardScene same = scene(9, 9, List.of(fill(a, Color.YELLOW.getRGB()), fill(b, Color.YELLOW.getRGB())));
        var merged = check(same, 10);
        assertEquals(2, merged.size());
        BoardScene different = scene(9, 9, List.of(fill(a, Color.YELLOW.getRGB()), fill(b, Color.CYAN.getRGB())));
        var divided = check(different, 12);
        assertEquals(List.of(Color.YELLOW.getRGB(), Color.CYAN.getRGB()), divided.stream().map(BoardTactical.Fill::argb).toList());
    }

    @Test
    void holesAndDisconnectedIslandsKeepEveryExposedBoundary() {
        Coords hole = new Coords(3, 3);
        List<BoardTactical.Fill> fills = new ArrayList<>();
        for (int direction = 0; direction < 6; direction++) {
            fills.add(fill(hole.translated(direction), Color.YELLOW.getRGB()));
        }
        fills.add(fill(new Coords(7, 7), Color.YELLOW.getRGB()));
        BoardScene scene = scene(9, 9, fills);
        var perimeter = check(scene, 30); // Six inner-hole edges, eighteen outer-ring edges, six island edges.
        assertEquals(7, perimeter.size());
        assertFalse(BoardDeploymentGeometry.zoneFills(scene).containsKey(hole));
        Area outline = area(perimeter);
        assertFalse(outline.contains(centerX(hole), centerY(hole)), "A hole must not be filled by its outline");
    }

    @Test
    void fullBoardKeepsMapEdgesButHasNoInteriorHexOutlines() {
        List<BoardTactical.Fill> fills = new ArrayList<>();
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) { fills.add(fill(new Coords(x, y), Color.YELLOW.getRGB())); }
        }
        BoardScene scene = scene(3, 3, fills);
        var perimeter = check(scene, 22);
        assertEquals(8, perimeter.size());
        assertFalse(perimeter.stream().anyMatch(fill ->
              BoardTacticalGeometry.borderCoords(scene, fill.border()).equals(new Coords(1, 1))));
        assertTrue(perimeter.stream().anyMatch(fill ->
              BoardTacticalGeometry.borderCoords(scene, fill.border()).equals(new Coords(0, 0))));
        assertTrue(BoardDeploymentGeometry.perimeter(scene, Map.of()).isEmpty());
    }

    @Test
    void perimeterUsesRaisedRangeWallsWithWhiteAnimatedOutlines() {
        Coords coords = new Coords(1, 1);
        BoardScene scene = scene(3, 3, List.of(fill(coords, Color.YELLOW.getRGB())));
        var perimeter = BoardDeploymentGeometry.perimeter(scene, BoardDeploymentGeometry.zoneFills(scene));
        var walls = BoardDeploymentGeometry.walls(scene, perimeter);
        assertEquals(6, walls.size());
        var finished = BoardTacticalGeometry.surfaces(scene);
        for (var wall : walls) {
            assertEquals(.5f, wall.border().height());
            assertEquals(166, wall.argb() >>> 24);
            assertEquals(Color.WHITE.getRGB(), wall.outline().argb());
            assertTrue(wall.outline().stroke().getDashArray().length > 0);
            List<BoardTacticalGeometry.Triangle> body = new ArrayList<>(), outline = new ArrayList<>();
            BoardTacticalGeometry.wall(scene, wall, false, body::add, (ignored, triangle) -> outline.add(triangle),
                  finished, new BoardTacticalGeometry.Clipper());
            assertEquals(2, body.size(), "Only two triangles per boundary edge, regardless of terrain complexity");
            float floor = body.getFirst().a().z;
            assertTrue(floor > finished.apply(coords).highestTop());
            assertEquals(floor + .5f * BoardGeometry.LEVEL, body.getFirst().c().z, .0001f);
            assertEquals(2, outline.size());
            assertTrue(outline.stream().allMatch(triangle -> triangle.a().z > floor));
        }
    }

    @Test
    void perimeterJoinsInteriorLevelsWithoutRisingToExteriorCliffs() {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        List<BoardTactical.Fill> fills = new ArrayList<>();
        Map<Coords, BoardTacticalGeometry.Surface> surfaces = new LinkedHashMap<>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                Coords coords = new Coords(x, y);
                // A zone crosses several elevation changes and borders a much taller exterior ridge.
                int level = x >= 3 ? 12 : (y % 3) * 2 - 2;
                tiles.add(BoardScenes.newTile(coords, level, -1, false, 0,
                      BoardScene.Surface.GRASS, List.of(), List.of()));
                if (x < 3) { fills.add(fill(coords, Color.YELLOW.getRGB())); }
                float crown = level * BoardGeometry.LEVEL + 4 * BoardGeometry.HEX_SCALE;
                var center = BoardGeometry.center(coords, 0);
                center.z = crown;
                var face = new BoardSurface.Face(center, new Vector3(center).add(1, 0, -1),
                      new Vector3(center).add(0, 1, -1), BoardSurface.Finish.TOP);
                surfaces.put(coords, new BoardTacticalGeometry.Surface(List.of(face), List.of(), List.of(face),
                      List.of(), List.of(), List.of()));
            }
        }
        var scene = new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of(), null,
              List.of(), List.of(), List.of(), new BoardTactical(fills, List.of()));
        var walls = BoardRangeBorder.join(BoardDeploymentGeometry.walls(scene,
              BoardDeploymentGeometry.perimeter(scene, BoardDeploymentGeometry.zoneFills(scene))));
        Map<Point, List<End>> joints = new LinkedHashMap<>();
        for (var wall : walls) {
            List<BoardTacticalGeometry.Triangle> body = new ArrayList<>(), outline = new ArrayList<>();
            BoardTacticalGeometry.wall(scene, wall, false, body::add, (ignored, triangle) -> outline.add(triangle),
                  surfaces::get, new BoardTacticalGeometry.Clipper());
            assertEquals(2, body.size());
            assertEquals(2, outline.size());
            var first = new End(wall.coords(), body.getFirst().a(), body.getLast().c());
            var second = new End(wall.coords(), body.getFirst().b(), body.getFirst().c());
            for (End end : List.of(first, second)) {
                assertTrue(end.top().z > surfaces.get(wall.coords()).highestTop() + .5f * BoardGeometry.LEVEL,
                      "The full half-level curtain must clear the owner's sculpted crown");
                joints.computeIfAbsent(new Point(Math.round(end.top().x * 1000), Math.round(end.top().y * 1000)),
                      ignored -> new ArrayList<>()).add(end);
            }
            assertEquals(first.top().z, outline.getFirst().a().z);
            assertEquals(second.top().z, outline.getFirst().b().z);
        }
        int levelChanges = 0, exteriorRidge = 0;
        for (var pair : joints.values()) {
            assertEquals(2, pair.size(), "Every point on this closed zone has two adjoining panels");
            End a = pair.getFirst(), b = pair.getLast();
            assertEquals(a.bottom().z, b.bottom().z, .001f, "Panel bottoms must share their joint");
            assertEquals(a.top().z, b.top().z, .001f, "The moving white outline must have no elevation gaps");
            if (scene.tile(a.owner()).elevation() != scene.tile(b.owner()).elevation()) { levelChanges++; }
            if (a.top().x >= 2.5f * BoardGeometry.TILE_WIDTH * BoardGeometry.HEX_SCALE) {
                assertTrue(a.top().z < 3 * BoardGeometry.LEVEL,
                      "The twelve-level exterior plateau must not raise the low deployment border");
                exteriorRidge++;
            }
        }
        assertTrue(levelChanges > 0);
        assertTrue(exteriorRidge > 0);
    }

    private record End(Coords owner, Vector3 bottom, Vector3 top) { }

    private static List<BoardTactical.Fill> check(BoardScene scene, int edges) {
        var zones = BoardDeploymentGeometry.zoneFills(scene);
        var result = BoardDeploymentGeometry.perimeter(scene, zones);
        assertEquals(edges, result.stream().mapToInt(fill -> fill.contours().size()).sum());
        Map<Integer, List<BoardTactical.Fill>> colors = new LinkedHashMap<>();
        for (var fill : result) {
            assertFalse(BoardDeploymentGeometry.isZone(fill), "A generated outline must not be converted again");
            assertNull(fill.planeAnchor(), "Overhead boundaries use the shared terrain-following range band");
            Coords owner = BoardTacticalGeometry.borderCoords(scene, fill.border());
            assertEquals(zones.get(owner).argb(), fill.argb());
            assertEquals(zones.get(owner).playback(), fill.playback());
            colors.computeIfAbsent(fill.argb(), ignored -> new ArrayList<>()).add(fill);
        }
        for (var entry : colors.entrySet()) {
            Area actual = area(entry.getValue());
            Area expected = expectedOutline(zones, entry.getKey());
            var bounds = expected.getBounds();
            for (int y = bounds.y - 1; y <= bounds.y + bounds.height; y++) {
                for (int x = bounds.x - 1; x <= bounds.x + bounds.width; x++) {
                    double px = x + .371, py = y + .237;
                    assertEquals(expected.contains(px, py), actual.contains(px, py),
                          () -> "Outline coverage at " + px + "," + py + " for color " + entry.getKey());
                }
            }
        }
        return result;
    }

    /** Independent construction: union complete hex footprints, then stroke only that union's inside boundary. */
    private static Area expectedOutline(Map<Coords, BoardTactical.Fill> zones, int argb) {
        Area union = new Area();
        for (var entry : zones.entrySet()) {
            if (entry.getValue().argb() != argb) { continue; }
            var centre = BoardTacticalGeometry.centre(entry.getKey());
            union.add(new Area(AffineTransform.getTranslateInstance(centre.x(), centre.y())
                  .createTransformedShape(BoardTacticalGeometry.insetHexagon(0))));
        }
        Area outline = new Area(new BasicStroke(20, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER).createStrokedShape(union));
        outline.intersect(union);
        return outline;
    }

    private static Area area(List<BoardTactical.Fill> fills) {
        Area result = new Area();
        fills.forEach(fill -> result.add(new Area(fill.shape())));
        return result;
    }

    /** A deployment hex's ring, as the deployment painter's capture reaches the 3D board. */
    private static BoardTactical.Fill fill(Coords coords, int argb) {
        var centre = BoardTacticalGeometry.centre(coords);
        return BoardTacticalGeometry.fill(BoardTacticalGeometry.ring(0, 2), centre, argb,
              BoardTactical.Playback.HIDE_DURING_MOVEMENT, new BoardTactical.HexBorder(centre, 0, 2, 1, true), centre);
    }

    private static float centerX(Coords coords) { return BoardTacticalGeometry.centre(coords).x(); }
    private static float centerY(Coords coords) { return BoardTacticalGeometry.centre(coords).y(); }

    private static BoardScene scene(int width, int height, List<BoardTactical.Fill> fills) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                tiles.add(BoardScenes.newTile(new Coords(x, y), 0, -1, false, 0,
                      BoardScene.Surface.GRASS, List.of(), List.of()));
            }
        }
        return new BoardScene(0, width, height, tiles, List.of(), List.of(), -1, "", List.of(), null,
              List.of(), List.of(), List.of(), new BoardTactical(fills, List.of()));
    }
}
