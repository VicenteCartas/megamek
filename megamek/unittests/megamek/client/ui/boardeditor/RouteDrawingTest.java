/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.boardeditor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.board.HexTile;
import megamek.common.board.RailRoute;
import megamek.common.event.board.BoardEvent;
import megamek.common.event.board.BoardListenerAdapter;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

/**
 * Routes drawn by hand (the user's design of 2026-10-11): Paint with a rail, maglev or road brush records the hexes the
 * drag enters and builds the route only on release, as one undo step; stepping back into the previous hex takes the last
 * one back, a skipped hex is filled, and Esc or a right click drops the drag. The preview shows the whole pending route,
 * with the turns too sharp to lay marked where the route rules make stubs.
 */
class RouteDrawingTest {
    private static final Coords START = new Coords(5, 2);

    @Test void theDocumentIsUntouchedUntilTheReleaseBuildsEveryHexAsOneUndoStep() {
        var session = rail();
        Map<Coords, JsonNode> document = document(session);
        AtomicInteger events = listen(session);
        List<Coords> cells = line(START, 3, 3, 3, 2, 2);
        drag(session, cells);
        assertEquals(0, events.get(), "No board event before the release");
        assertEquals(document, document(session), "The drag changes nothing");
        assertFalse(session.snapshot().canUndo());
        assertTrue(session.snapshot().drawsRoutes());

        // Meanwhile the preview is the whole route, as the release will build it.
        var preview = session.preview(cells.getLast(), 0, 0, "ground");
        assertEquals(cells, preview.path());
        assertTrue(preview.after().keySet().containsAll(cells));
        assertEquals(0, events.get(), "The preview is unseen");
        assertEquals(document, document(session));

        session.finishStroke();
        for (int i = 0; i < cells.size(); i++) {
            var marker = route(session, cells.get(i));
            assertNotNull(marker, cells.get(i) + " is built");
            if (i > 0) { assertNotNull(RailRoute.joined(marker, cells.get(i).direction(cells.get(i - 1)), session.board().getHex(cells.get(i - 1)))); }
        }
        preview.after().forEach((at, hex) -> assertEquals(normal(BoardFile.hexNode(hex)),
              normal(BoardFile.hexNode(session.board().getHex(at))), "The release builds what the preview showed at " + at));
        session.command(new Command(Action.UNDO), null);
        assertEquals(document, document(session), "One undo step takes the whole route back");
        assertFalse(session.snapshot().canUndo());
    }

    @Test void steppingBackIntoThePreviousHexTakesHexesBackBeforeTheRelease() {
        var session = rail();
        List<Coords> cells = line(START, 3, 3, 3, 3);
        drag(session, cells);
        // Back one hex, then two more along the path, then on in another direction.
        session.pointer(cells.get(3), 0, 0, true);
        session.pointer(cells.get(2), 0, 0, true);
        session.pointer(cells.get(1), 0, 0, true);
        Coords turn = cells.get(1).translated(2);
        session.pointer(turn, 0, 0, true);
        session.finishStroke();
        assertNotNull(route(session, cells.get(0)));
        assertNotNull(route(session, cells.get(1)));
        assertNotNull(route(session, turn));
        for (Coords retraced : cells.subList(2, 5)) { assertNull(route(session, retraced), retraced + " was taken back"); }
        assertEquals(RailRoute.side(0) | RailRoute.side(2), route(session, cells.get(1)).connections(), "The kept hex turns");
    }

    @Test void reenteringAnOlderHexOfThePathCrossesTheRouteInOneStroke() {
        var session = rail();
        // South three, then round by gentle turns (SE, NE, N, NW) and back south-west through the third hex.
        List<Coords> cells = line(START, 3, 3, 3, 2, 1, 0, 5, 4, 4);
        drag(session, cells);
        assertEquals(cells, session.preview(cells.getLast(), 0, 0, "ground").path());
        assertTrue(session.preview(cells.getLast(), 0, 0, "ground").stubs().isEmpty(), "Every turn of the loop is gentle");
        session.finishStroke();
        Coords crossing = cells.get(2);
        cells.forEach(at -> assertNotNull(route(session, at), at + " keeps its waypoint: nothing was cut"));
        var marker = route(session, crossing);
        int sides = RailRoute.side(0) | RailRoute.side(3) | RailRoute.side(1) | RailRoute.side(4);
        assertEquals(sides, marker.connections());
        int pieces = RailRoute.pieces(marker, sides);
        assertEquals(1 << RailRoute.piece(0, 3) | 1 << RailRoute.piece(1, 4), pieces, "Two straights cross");
        assertEquals(1, RailRoute.markers(session.board().getHex(crossing), RailRoute.Kind.RAIL).size());
        assertTrue(session.snapshot().canUndo());
        session.command(new Command(Action.UNDO), null);
        assertNull(route(session, crossing), "The loop is one undo step");
    }

    /**
     * A hex is taken where the pointer reaches its core: grazing the corner of the hex south of the start on the way to
     * the one south-east adds no waypoint there, so no sharp turn either.
     */
    @Test void grazingAHexsCornerAddsNoWaypoint() {
        var session = rail();
        Coords grazed = START.translated(3), next = START.translated(2);
        session.pointer(START, 0, 0, false);
        // Just inside the grazed hex, by its corner shared with the start and the next hex.
        assertFalse(BoardEditorSession.core(.23, .45));
        session.pointer(grazed, .23, .45, true);
        session.pointer(next, 0, 0, true);
        assertEquals(List.of(START, next), session.preview(next, 0, 0, "ground").path());
        assertTrue(session.preview(next, 0, 0, "ground").stubs().isEmpty());
        session.finishStroke();
        assertNull(route(session, grazed));
        assertEquals(RailRoute.side(2), route(session, START).connections());
    }

    /**
     * The release builds the hex the pointer is in, as the preview shows it, also where the pointer has not reached that
     * hex's core: just inside the border of the next hex, and back inside the border of the previous one, which takes
     * the last hex back.
     */
    @Test void theReleaseBuildsWhatThePreviewShowsWithThePointerInAHexsBorder() {
        Coords south = START.translated(3);
        // Into the hex to the south, just inside its north border.
        assertReleaseBuildsThePreview(List.of(START), south, 0, .35, List.of(START, south));
        // Two hexes south, then back north just inside the first one's south border.
        assertReleaseBuildsThePreview(line(START, 3, 3), south, 0, -.35, List.of(START, south));
    }

    /**
     * A bend drawn through a hex's border, without reaching its core, goes through that hex: whichever way it turns, the
     * route takes the hex the pointer crossed, not the other one beside both ends.
     */
    @Test void aBendDrawnThroughAHexsBorderGoesThroughThatHex() {
        Coords start = new Coords(7, 8);
        for (int direction = 0; direction < 6; direction++) {
            for (int bend : new int[] { 1, 5 }) {
                var session = rail();
                Coords crossed = start.translated(direction), end = crossed.translated((direction + bend) % 6);
                session.pointer(start, 0, 0, false);
                // Just inside the crossed hex's border towards the start.
                float[] border = RailRoute.border((direction + 3) % 6);
                double x = border[0] / HexTile.MODEL_WIDTH * .7, y = border[1] / HexTile.HEIGHT * .7;
                assertFalse(BoardEditorSession.core(x, y));
                session.pointer(crossed, x, y, true);
                session.pointer(end, 0, 0, true);
                assertEquals(List.of(start, crossed, end), session.preview(end, 0, 0, "ground").path(), direction + " bending " + bend);
            }
        }
    }

    @Test void escOrARightClickDropsTheDragAndAClickWithoutADragPlacesItsMarkerAsBefore() {
        var session = rail();
        Map<Coords, JsonNode> document = document(session);
        drag(session, line(START, 3, 3, 2));
        session.cancelStroke();
        assertTrue(session.preview(START.translated(3), 0, 0, "ground").path().isEmpty(), "Nothing is being drawn any more");
        session.finishStroke();
        assertEquals(document, document(session), "A dropped drag changes nothing");
        assertFalse(session.snapshot().canUndo(), "and leaves no undo step");

        // A click: a marker without sides, the way a route is started.
        session.pointer(START, 0, 0, false);
        session.finishStroke();
        assertEquals(0, route(session, START).connections());
        assertTrue(session.snapshot().canUndo());
    }

    @Test void aDrawnRouteJoinsExistingTrackAtBothEnds() {
        var session = rail();
        List<Coords> west = line(new Coords(2, 1), 3, 3), east = line(new Coords(8, 1), 3, 3);
        drag(session, west); session.finishStroke();
        drag(session, east); session.finishStroke();
        // From the west route's end to the east route's end.
        session.pointer(west.getLast(), 0, 0, false);
        session.pointer(east.getLast(), 0, 0, true);
        session.finishStroke();
        var run = RailRoute.run(session.board()::getHex, west.getFirst(), route(session, west.getFirst()));
        assertTrue(run.stream().anyMatch(member -> member.at().equals(east.getFirst())), "One connected route: " + run);
        assertEquals(1, RailRoute.markers(session.board().getHex(west.getLast()), RailRoute.Kind.RAIL).size(), "Continued, not doubled");
        assertEquals(1, RailRoute.markers(session.board().getHex(east.getLast()), RailRoute.Kind.RAIL).size());
    }

    /**
     * A turn sharper than a gentle pair (south, then north-east) ends in stubs: the preview marks exactly the hexes where
     * the release then leaves stranded sides, and only those.
     */
    @Test void redMarksStandExactlyWhereTheRouteRulesMakeStubs() {
        for (var kind : RailRoute.Kind.values()) {
            var session = new BoardEditorSession();
            session.command(new Command(Action.ASSET, "", kind.asset), null);
            List<Coords> cells = line(START, 3, 3, 1, 1, 1);
            drag(session, cells);
            List<Coords> marked = session.preview(cells.getLast(), 0, 0, "ground").stubs();
            assertEquals(List.of(cells.get(2)), marked, kind + ": the hex entered from the north and left to the north-east");
            session.finishStroke();
            List<Coords> stubbed = new ArrayList<>();
            for (Coords at : cells) {
                var hex = session.board().getHex(at);
                var marker = RailRoute.find(hex, kind);
                if (RailRoute.stranded(RailRoute.drawnSides(marker, hex)) != 0 && !stubbed.contains(at)) { stubbed.add(at); }
            }
            assertEquals(marked, stubbed, kind + ": the release's stubs");
        }
    }

    /**
     * The pointer heading sharply aside in the path's last hex: the preview shows the step it heads for, whose sharp turn
     * would end in stubs, but marks nothing red, since a release there builds a plain dead end.
     */
    @Test void aStepThePointerOnlyHeadsForMarksNoStub() {
        var session = rail();
        List<Coords> cells = line(START, 3, 3);
        Coords end = cells.getLast(), aside = end.translated(1);
        drag(session, cells);
        float[] border = RailRoute.border(1);
        double x = border[0] / HexTile.MODEL_WIDTH * .5, y = border[1] / HexTile.HEIGHT * .5;
        assertTrue(BoardEditorSession.core(x, y), "Still in the core of " + end);
        session.pointer(end, x, y, true);
        var preview = session.preview(end, x, y, "ground");
        assertEquals(cells, preview.path());
        assertEquals(aside, preview.next(), "The step it heads for shows");
        var forecast = RailRoute.find(preview.after().get(end), RailRoute.Kind.RAIL);
        assertNotEquals(0, RailRoute.stranded(forecast.connections()), "That step would turn sharply");
        assertEquals(List.of(), preview.stubs(), "No release leaves a stub here");
        session.finishStroke();
        assertEquals(RailRoute.side(0), route(session, end).connections(), "A plain dead end");
        assertNull(route(session, aside));
    }

    @Test void anSShapedRoadIsPaintedOnReleaseAndJoinsAlongItsPath() {
        var session = new BoardEditorSession();
        session.command(new Command(Action.CHOOSE_BRUSH, "road", "1"), null);
        assertTrue(session.snapshot().drawsRoutes());
        List<Coords> cells = line(new Coords(3, 2), 3, 2, 2, 3, 4, 4, 3);
        Map<Coords, JsonNode> document = document(session);
        drag(session, cells);
        assertEquals(document, document(session), "Nothing painted before the release");
        var preview = session.preview(cells.getLast(), 0, 0, "ground");
        assertEquals(cells, preview.path());
        session.finishStroke();
        for (int i = 0; i < cells.size(); i++) {
            var hex = session.board().getHex(cells.get(i));
            assertTrue(hex.containsTerrain(Terrains.ROAD), cells.get(i) + " is a road");
            if (i > 0) {
                assertTrue(hex.containsTerrainExit(Terrains.ROAD, cells.get(i).direction(cells.get(i - 1))), cells.get(i) + " joins the hex before");
            }
        }
        // Every way card is a single route; an old brush radius cannot widen a click.
        session.command(new Command(Action.BRUSH, "", "2"), null);
        session.pointer(new Coords(10, 10), 0, 0, false);
        session.finishStroke();
        assertEquals(1, session.brush(new Coords(10, 10)).stream().filter(at -> session.board().getHex(at).containsTerrain(Terrains.ROAD)).count());
    }

    /** Roads connect as buildings do: along the stroke only, so a road or pavement beside it stays apart. */
    @Test void aRoadConnectsOnlyAlongItsStroke() {
        Coords imported = new Coords(5, 8), first = new Coords(5, 5), second = first.translated(0), beside = first.translated(1);
        Coords far = beside.translated(1);
        Board board = Board.createEmptyBoard(16, 17);
        board.setHex(imported, new Hex(0, "road:1", ""));
        board.setHex(far.translated(0), new Hex(0, "pavement:1", ""));
        var session = new BoardEditorSession();
        session.open(board, null);
        session.command(new Command(Action.CHOOSE_BRUSH, "road", "1"), null);
        drag(session, List.of(first, second));
        session.finishStroke();
        drag(session, List.of(beside, far));
        session.finishStroke();
        assertEquals(1, roadExits(session, first));
        assertEquals(8, roadExits(session, second));
        assertEquals(2, roadExits(session, beside), "No side toward the road beside it");
        assertEquals(16, roadExits(session, far), "No side toward the pavement beside it");

        // A click beside an imported road leaves both apart, as they would be after saving and reopening.
        session.pointer(imported.translated(3), 0, 0, false);
        session.finishStroke();
        assertEquals(0, roadExits(session, imported));
        assertEquals(0, roadExits(session, imported.translated(3)));

        // A stroke into an existing road joins it and keeps its branches.
        int side = beside.direction(second);
        drag(session, List.of(beside, second));
        session.finishStroke();
        assertEquals(2 | 1 << side, roadExits(session, beside));
        assertEquals(8 | 1 << (side + 3) % 6, roadExits(session, second));

        // Erasing a hex disconnects its neighbours in the same step.
        session.command(new Command(Action.TOOL, "", "ERASE"), null);
        session.pointer(far, 0, 0, false);
        session.finishStroke();
        assertEquals(1 << side, roadExits(session, beside));
        session.command(new Command(Action.UNDO), null);
        assertEquals(2 | 1 << side, roadExits(session, beside));
    }

    private static int roadExits(BoardEditorSession session, Coords at) {
        return session.board().getHex(at).getTerrain(Terrains.ROAD).getExits();
    }

    private static BoardEditorSession rail() {
        var session = new BoardEditorSession();
        session.command(new Command(Action.ASSET, "", RailRoute.Kind.RAIL.asset), null);
        return session;
    }

    /** The press on the first cell and a drag through the others; no release. */
    private static void drag(BoardEditorSession session, List<Coords> cells) {
        for (int i = 0; i < cells.size(); i++) { session.pointer(cells.get(i), 0, 0, i > 0); }
    }

    /**
     * A drag through {@code cells}' centres, then into {@code at} at {@code x, y} in its border, and the release there: it
     * builds the preview's path, {@code built}, exactly as the preview showed it.
     */
    private static void assertReleaseBuildsThePreview(List<Coords> cells, Coords at, double x, double y, List<Coords> built) {
        var session = rail();
        drag(session, cells);
        assertFalse(BoardEditorSession.core(x, y), "In the border of " + at);
        session.pointer(at, x, y, true);
        var preview = session.preview(at, x, y, "ground");
        assertEquals(built, preview.path());
        session.finishStroke();
        preview.after().forEach((cell, hex) -> assertEquals(normal(BoardFile.hexNode(hex)),
              normal(BoardFile.hexNode(session.board().getHex(cell))), "The release builds what the preview showed at " + cell));
        for (Coords cell : cells) { assertEquals(built.contains(cell), route(session, cell) != null, cell + " is built: " + built); }
        assertNotNull(route(session, at), at + " is built");
    }

    private static List<Coords> line(Coords start, int... directions) {
        List<Coords> result = new ArrayList<>(List.of(start));
        for (int direction : directions) { result.add(result.getLast().translated(direction)); }
        return result;
    }

    private static BoardDecoration route(BoardEditorSession session, Coords at) {
        return RailRoute.find(session.board().getHex(at), RailRoute.Kind.RAIL);
    }

    private static AtomicInteger listen(BoardEditorSession session) {
        AtomicInteger events = new AtomicInteger();
        session.board().addBoardListener(new BoardListenerAdapter() {
            @Override public void boardChangedHex(BoardEvent event) { events.incrementAndGet(); }
            @Override public void boardChangedAllHexes(BoardEvent event) { events.incrementAndGet(); }
        });
        return events;
    }

    private static Map<Coords, JsonNode> document(BoardEditorSession session) {
        Map<Coords, JsonNode> result = new LinkedHashMap<>();
        for (int x = 0; x < session.board().getWidth(); x++) {
            for (int y = 0; y < session.board().getHeight(); y++) {
                result.put(new Coords(x, y), BoardFile.hexNode(session.board().getHex(x, y)));
            }
        }
        return result;
    }

    /** A hex's file form without its objects' ids, which every build makes new. */
    private static JsonNode normal(JsonNode hex) {
        JsonNode copy = hex.deepCopy();
        if (copy.has("decorations")) {
            copy.get("decorations").forEach(object -> ((com.fasterxml.jackson.databind.node.ObjectNode) object).remove(List.of("id", "group")));
        }
        return copy;
    }
}
