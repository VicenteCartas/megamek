/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.BasicStroke;
import java.awt.geom.Area;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.board.HexTile;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The reported four three-hex domes: logical links can cross extra geometric share cuts when fitted to a ring. */
class DomeConnectionTest {
    private static BoardScene scene;
    private static final Map<Coords, BoardMultiHexBuilding.Layout> LAYOUTS = new LinkedHashMap<>();

    @BeforeAll
    static void reportedBoard() throws Exception {
        GdxNativesLoader.load();
        scene = scene(reported());
        for (var tile : scene.tiles()) {
            var feature = tile.features().stream().filter(f -> BoardMultiHexBuilding.supports(f.asset())).findFirst().orElse(null);
            if (feature != null) { LAYOUTS.put(tile.coords(), BoardMultiHexBuilding.layout(scene, tile, feature)); }
        }
    }

    private static Board reported() throws Exception {
        try (var input = DomeConnectionTest.class.getResourceAsStream("dome-triangle-connections.board2")) {
            assertNotNull(input);
            return BoardFile.readNative(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static BoardScene scene(Board board) throws Exception {
        var task = new FutureTask<BoardScene>(() -> {
            var editor = new BoardEditorSession(); editor.open(board, null);
            try (var source = new GpuMapSource(editor.game(), null, editor)) { return source.takeFrame().scene(); }
        });
        SwingUtilities.invokeAndWait(task); return task.get();
    }

    @Test
    void touchingBuildingsWithExplicitlyDisconnectedExitsDoNotGainACorridor() throws Exception {
        var board = reported();
        Coords junction = new Coords(10, 9), detached = junction.translated(5);
        var source = board.getHex(junction).getTerrain(Terrains.BUILDING);
        var target = board.getHex(detached).getTerrain(Terrains.BUILDING);
        source.setExits(source.getExits() & ~(1 << 5)); target.setExits(target.getExits() & ~(1 << 2));
        var separate = scene(board);
        var tile = separate.tile(detached);
        var feature = tile.features().stream().filter(f -> BoardMultiHexBuilding.supports(f.asset())).findFirst().orElseThrow();
        var group = BoardMultiHexBuilding.group(separate, tile, feature.asset());
        assertEquals(3, group.size(), "The detached triangular cluster stays a separate authored building");
        for (var member : group) {
            var part = separate.tile(member.coords());
            assertTrue(BoardMultiHexBuilding.layout(separate, part, feature).tunnels().isEmpty(),
                  "Fitting a neighbouring link must not open a passage into a disconnected dome");
        }
    }

    @Test
    void fittingTheLinksKeepsAllFourDomesAndTheAuthoredCorridorHeight() {
        assertEquals(13, LAYOUTS.size());
        assertEquals(4, LAYOUTS.get(new Coords(9, 8)).domes().size());
        for (var layout : LAYOUTS.values()) {
            for (var tunnel : layout.tunnels()) {
                assertEquals(GpuBuilding.LEVEL_HEIGHT, tunnel.top(), .001,
                      "Fitting the landing must not lower the authored corridor to the small dome's ring height");
            }
        }
    }

    @Test
    void continuousAuthorizedCorridorsHaveNoWallsAcrossTheirExtraHexCuts() {
        int checked = 0, withoutLogicalExit = 0;
        for (var entry : LAYOUTS.entrySet()) {
            var tile = scene.tile(entry.getKey());
            for (var tunnel : entry.getValue().tunnels()) {
                for (int edge = 0; edge < tunnel.outline().size(); edge++) {
                    var a = tunnel.outline().get(edge); var b = tunnel.outline().get((edge + 1) % tunnel.outline().size());
                    for (int direction = 0; direction < 6; direction++) {
                        double angle = Math.toRadians(90 - 60 * direction), nx = Math.cos(angle), ny = Math.sin(angle);
                        if (Math.abs(a.x() * nx + a.y() * ny - HexTile.HEIGHT / 2) > .02
                              || Math.abs(b.x() * nx + b.y() * ny - HexTile.HEIGHT / 2) > .02) { continue; }
                        Coords next = tile.coords().translated(direction);
                        var neighbour = LAYOUTS.get(next);
                        if (neighbour == null) { continue; }
                        double x = (a.x() + b.x()) / 2 + nx * .1 + HexTile.centerX(tile.coords(), HexTile.MODEL_WIDTH)
                              - HexTile.centerX(next, HexTile.MODEL_WIDTH);
                        double y = (a.y() + b.y()) / 2 + ny * .1 + HexTile.centerY(tile.coords(), HexTile.HEIGHT)
                              - HexTile.centerY(next, HexTile.HEIGHT);
                        for (var other : neighbour.tunnels()) {
                            if (!area(other.outline()).contains(x, y)) { continue; }
                            assertEquals(Math.min(tunnel.top(), other.top()), tunnel.openings().get(edge), .001,
                                  tile.coords().getBoardNum() + " must not put a facade across a continuous generated passage");
                            checked++;
                            if ((tile.cover().exits() & 1 << direction) == 0) { withoutLogicalExit++; }
                        }
                    }
                }
            }
        }
        assertTrue(checked >= 8);
        assertTrue(withoutLogicalExit > 0, "Exercise the extra slice crossings created by fitting the triangular clusters");
    }

    @Test
    void dockingRoofsStopAtThePodiumWithoutCoveringItsRingOrGlass() throws Exception {
        var method = BoardMultiHexBuilding.class.getDeclaredMethod("corridorBlock", GeneratedMesh.class, BoardMultiHexBuilding.Tunnel.class);
        method.setAccessible(true);
        int checked = 0;
        for (var layout : LAYOUTS.values()) {
            for (var tunnel : layout.tunnels()) {
                var mesh = new GeneratedMesh(); method.invoke(null, mesh, tunnel);
                var vertices = mesh.parts.get(BoardMultiHexBuilding.ROOF);
                // A tiny clipped share can be entirely occupied by the coping bevel, with no flat roof panel.
                if (vertices == null) { continue; }
                List<Vector3> triangles = new ArrayList<>();
                for (int i = 0; i < vertices.size; i += RigidGlb.STRIDE) {
                    triangles.add(new Vector3(vertices.get(i), vertices.get(i + 1), vertices.get(i + 2)));
                }
                Area roof = GpuBuildingInterior.area(triangles);
                for (var dome : layout.domes()) {
                    List<BoardMultiHexBuilding.Point> points = new ArrayList<>();
                    for (int side = 0; side < 12; side++) {
                        double angle = side * Math.PI / 6;
                        points.add(new BoardMultiHexBuilding.Point(dome.x() + dome.podium() * (float) Math.cos(angle),
                              dome.y() + dome.podium() * (float) Math.sin(angle)));
                    }
                    Area ring = area(points);
                    ring.subtract(new Area(new BasicStroke(.04f).createStrokedShape(ring)));
                    ring.intersect(roof);
                    assertTrue(ring.isEmpty(), "An oblique landing must fit the ring wall across its whole width");
                }
                checked++;
            }
        }
        assertTrue(checked >= 5);
    }

    @Test
    void landingsDoNotCreateOverlappingPairsOfCornerPillars() {
        List<Vector3> pillars = new ArrayList<>();
        for (var entry : LAYOUTS.entrySet()) {
            float x = HexTile.centerX(entry.getKey(), HexTile.MODEL_WIDTH), y = HexTile.centerY(entry.getKey(), HexTile.HEIGHT);
            for (var tunnel : entry.getValue().tunnels()) {
                for (var corner : tunnel.corners()) {
                    if (corner.bottom() <= 0) { continue; }
                    var at = new Vector3(corner.at().x() + x, corner.at().y() + y, corner.bottom());
                    for (var other : pillars) {
                        assertTrue(at.dst(other) > 3,
                              "A tiny hex/ring intersection must not produce two overlapping full-width pillar caps");
                    }
                    pillars.add(at);
                }
            }
        }
        assertTrue(pillars.size() >= 4, "Check actual supports at the four landings");
    }

    private static Area area(List<BoardMultiHexBuilding.Point> points) {
        Path2D.Float path = new Path2D.Float();
        path.moveTo(points.getFirst().x(), points.getFirst().y());
        for (var p : points) { path.lineTo(p.x(), p.y()); }
        path.closePath(); return new Area(path);
    }

    @Test
    void theFoundationHasSideWallsAlongItsWholeOuterBoundary() throws Exception {
        var method = BoardMultiHexBuilding.class.getDeclaredMethod("skirt", GeneratedMesh.class,
              BoardMultiHexBuilding.Layout.class, int.class);
        method.setAccessible(true);
        Area deck = new Area(), sides = new Area();
        for (var entry : LAYOUTS.entrySet()) {
            var mesh = new GeneratedMesh(); method.invoke(null, mesh, entry.getValue(), 0);
            var vertices = mesh.parts.get(BoardMultiHexBuilding.PLINTH_MATERIAL);
            if (vertices == null) { continue; }
            float x = HexTile.centerX(entry.getKey(), HexTile.MODEL_WIDTH), y = HexTile.centerY(entry.getKey(), HexTile.HEIGHT);
            for (int i = 0; i < vertices.size; i += 3 * RigidGlb.STRIDE) {
                List<Vector3> top = new ArrayList<>();
                for (int corner = 0; corner < 3; corner++) {
                    int at = i + corner * RigidGlb.STRIDE;
                    if (Math.abs(vertices.get(at + 2) - BoardMultiHexBuilding.PLINTH) < .001) {
                        top.add(new Vector3(vertices.get(at) + x, vertices.get(at + 1) + y, vertices.get(at + 2)));
                    }
                }
                if (top.size() == 3 && vertices.get(i + 5) > .99f) { deck.add(GpuBuildingInterior.area(top)); }
                else if (vertices.get(i + 5) < .01f) {
                    for (int first = 0; first < top.size(); first++) {
                        for (int second = first + 1; second < top.size(); second++) {
                            var a = top.get(first); var b = top.get(second);
                            sides.add(new Area(new BasicStroke(.3f).createStrokedShape(new Line2D.Float(a.x, a.y, b.x, b.y))));
                        }
                    }
                }
            }
        }
        assertFalse(deck.isEmpty()); assertFalse(sides.isEmpty());
        // Independently clipped shares can differ by a few float ulps. Close those hairline joins before tracing
        // the actual exterior; an unowned wedge is orders of magnitude wider.
        deck.add(new Area(new BasicStroke(.02f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(deck)));
        Area missing = new Area(new BasicStroke(.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(deck));
        missing.subtract(sides);
        assertTrue(missing.isEmpty(), "A foundation clipped outside its owning hexes leaves an open notch down to the grass: "
              + missing.getBounds2D());
    }
}
