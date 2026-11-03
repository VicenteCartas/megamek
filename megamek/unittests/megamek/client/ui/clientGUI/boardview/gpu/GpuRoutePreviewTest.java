/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GpuRoutePreviewTest {
    @ParameterizedTest
    @ValueSource(strings = { "road", "rail", "maglev", "wall", "bridge" })
    void pendingNeighbourhoodMatchesCommittedConnections(String kind) {
        var session = new BoardEditorSession();
        session.game().setBoard(Board.createEmptyBoard(12, 12));
        choose(session, kind);
        Coords start = new Coords(5, 3), join = start.translated(3, 2);
        drag(session, List.of(start, start.translated(3), join));
        session.finishStroke();
        var before = document(session);
        // Extend an existing end into a bend. Its old terminal and its neighbour's curve must also update.
        var path = List.of(join, join.translated(2), join.translated(2, 2));
        drag(session, path);
        try (var capture = new BoardTiles(new BoardScene.PixelPool())) {
            var pending = data(session, capture, path.getLast());
            assertEquals(before, document(session), "Capturing a preview never writes the board");
            assertTrue(pending.routes().containsKey(start), "The existing connection's neighbourhood is captured too");
            assertTrue(pending.routes().keySet().containsAll(path));
            session.finishStroke();
            var committed = BoardTiles.captureAll(session.board());
            for (var entry : pending.routes().entrySet()) {
                Coords at = entry.getKey();
                var actual = committed.get(at.getX() * session.board().getHeight() + at.getY());
                assertEquals(actual.features(), entry.getValue().features(), "Fitted buffers and signals at " + at);
                assertEquals(actual.roadExits(), entry.getValue().roadExits(), "Road joins at " + at);
                assertEquals(actual.appearance(), entry.getValue().appearance(), "Authored route appearance at " + at);
                assertTrue(entry.getValue().sameGeometry(actual), kind + " has the committed geometry at " + at);
            }
            session.command(new Command(Action.UNDO), null);
            assertEquals(before, document(session), "The preview adds no undo step");
        }
    }

    @Test void paintUsesTheMovementDestinationGhostMaterial() {
        Material expected = new Material(ColorAttribute.createDiffuse(.2f, .3f, .4f, 1));
        Material actual = expected.copy();
        GpuBoardOverlay.ghostly(expected);
        GpuEditorPreview.ghostly(actual, false);
        assertEquals(expected, actual);
        assertFalse(actual.get(DepthTestAttribute.class, DepthTestAttribute.Type).depthMask);
    }

    @Test void roadComparisonIgnoresPaintOutsideTheReceivingTrianglesButKeepsVisibleColourChanges() {
        Coords at = new Coords(4, 3);
        var a = BoardGeometry.center(at, 0);
        float scale = BoardGeometry.hexScale();
        var triangles = List.of(new BoardTacticalGeometry.Triangle(a,
              a.cpy().add(20 * scale, 0, 0), a.cpy().add(0, 20 * scale, 0), -1));
        var paint = new GpuRoads.Patch(new Area(new Rectangle2D.Float(-2, -2, 24, 24)), "roads/asphalt", Color.WHITE, 20, 0, null);
        var original = GpuRoads.mask(paint, (x, y, scratch) -> Color.WHITE);
        // Inside the carrier's bounds, but beyond its diagonal edge: a neighbouring bend's paint.
        var outside = GpuRoads.mask(paint, (x, y, scratch) -> x > 15 && y > 15 ? Color.RED : Color.WHITE);
        var inside = GpuRoads.mask(paint, (x, y, scratch) -> x < 5 && y < 5 ? Color.RED : Color.WHITE);
        assertNotEquals(original, outside);
        assertEquals(GpuRoads.visibleMask(at, original, triangles), GpuRoads.visibleMask(at, outside, triangles));
        assertNotEquals(GpuRoads.visibleMask(at, original, triangles), GpuRoads.visibleMask(at, inside, triangles));
        var elsewhere = new GpuRoads.Patch(new Area(new Rectangle2D.Float(30, 30, 10, 10)), "roads/asphalt", Color.WHITE, 20, 0, null);
        assertNull(GpuRoads.visibleMask(at, GpuRoads.mask(elsewhere, (x, y, scratch) -> Color.WHITE), triangles));
    }

    static void choose(BoardEditorSession session, String kind) {
        if (List.of("building", "habitat", "fortress").contains(kind)) {
            session.command(new Command(Action.CHOOSE_BRUSH, "building", kind.equals("habitat") ? "building/lunar-habitat" : ""), null);
            if (kind.equals("fortress")) { session.command(new Command(Action.BRUSH_VALUE, "terrain:bldg_class", "2"), null); }
        } else if (kind.equals("rail") || kind.equals("maglev")) {
            var route = kind.equals("rail") ? RailRoute.Kind.RAIL : RailRoute.Kind.MAGLEV;
            session.command(new Command(Action.ASSET, "", route.asset), null);
        }
        else {
            session.command(new Command(Action.CHOOSE_BRUSH, kind.equals("wall") ? "building" : kind,
                  kind.equals("wall") ? "wall" : kind.equals("road") ? "1" : "bridge/truss"), null);
            if (kind.equals("bridge")) { session.command(new Command(Action.BRUSH_VALUE, "wayOffset", "2"), null); }
        }
    }

    static void drag(BoardEditorSession session, List<Coords> path) {
        for (int i = 0; i < path.size(); i++) { session.pointer(path.get(i), 0, 0, i > 0); }
    }

    static GpuEditorPreview.Data data(BoardEditorSession session, BoardTiles capture, Coords end) {
        var edit = session.preview(end, 0, 0, "ground");
        return GpuEditorPreview.data(edit, session.snapshot().tool() == BoardEditorSession.Tool.ERASE,
              session.snapshot().activeBrush(), session.snapshot().drawsRoutes(),
              (at, hex) -> capture.preview(session.board(), at, hex,
              next -> edit.after().getOrDefault(next, session.board().getHex(next))));
    }

    private static List<Object> document(BoardEditorSession session) {
        List<Object> result = new ArrayList<>();
        for (int x = 0; x < session.board().getWidth(); x++) {
            for (int y = 0; y < session.board().getHeight(); y++) { result.add(BoardFile.hexNode(session.board().getHex(x, y))); }
        }
        return result;
    }
}
