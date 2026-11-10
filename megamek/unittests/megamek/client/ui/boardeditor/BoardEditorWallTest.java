/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.boardeditor;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;

import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.Hex;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoardEditorWallTest {
    @TempDir Path directory;
    private static final Coords CENTER = new Coords(5, 5);

    @Test
    void wallDrawingKeepsItsKindWhenItsIndependentWeightChanges() {
        var blueprint = BoardEditorBlueprint.get();
        assertNotNull(blueprint.entry("building/wall"));
        var session = wallBrush();
        assertTrue(session.snapshot().drawsRoutes());
        assertEquals(5, session.snapshot().activeBrush().property("building").value());
        send(session, Action.BRUSH_VALUE, "terrain:building", "4");
        assertEquals(5, session.snapshot().activeBrush().property("building").value());
        send(session, Action.BRUSH_VALUE, "terrain:bldg_weight", "2");
        assertEquals(2, session.snapshot().activeBrush().property("bldg_weight").value());
        assertEquals(5, session.snapshot().activeBrush().property("building").value());
        assertTrue(session.snapshot().drawsRoutes());
        assertTrue(blueprint.component("building").fields().stream().filter(field -> field.terrain().equals("building"))
              .flatMap(field -> field.choices().stream()).noneMatch(choice -> choice.value() == 5));
    }

    @Test
    void aBuildingsWeightSetsTheTypeTheRulesKnowAndOnlyCivilianAndCastleBrianKeepTheirOwn() {
        var session = new BoardEditorSession();
        send(session, Action.CHOOSE_BRUSH, "building", "default");
        send(session, Action.BRUSH_VALUE, "terrain:bldg_weight", "0");
        session.pointer(CENTER, 0, 0, false); session.finishStroke();
        Hex civilian = session.board().getHex(CENTER);
        assertEquals(1, civilian.terrainLevel(Terrains.BUILDING), "Light to the rules");
        assertEquals(0, civilian.terrainLevel(Terrains.BLDG_WEIGHT));
        send(session, Action.TERRAIN, "bldg_weight", "5");
        assertEquals(4, session.board().getHex(CENTER).terrainLevel(Terrains.BUILDING), "Hardened to the rules");
        assertEquals(5, session.board().getHex(CENTER).terrainLevel(Terrains.BLDG_WEIGHT));
        send(session, Action.TERRAIN, "bldg_weight", "3");
        assertEquals(3, session.board().getHex(CENTER).terrainLevel(Terrains.BUILDING));
        assertFalse(session.board().getHex(CENTER).containsTerrain(Terrains.BLDG_WEIGHT), "Heavy is its type alone");
    }

    @Test
    void aCardsBrushGivenAnotherWeightOrClassIsThatCardsBrush() {
        var session = new BoardEditorSession();
        send(session, Action.CHOOSE_BRUSH, "building", "wall-castle-brian");
        send(session, Action.BRUSH_VALUE, "terrain:bldg_weight", "2");
        var medium = session.snapshot().activeBrush();
        assertEquals("building/wall-medium", medium.key());
        assertEquals("Medium", medium.label());
        assertEquals(40, medium.property("bldg_cf").value(), "With the card's own values");
        send(session, Action.CHOOSE_BRUSH, "building", "default");
        send(session, Action.BRUSH_VALUE, "terrain:bldg_class", "1");
        send(session, Action.BRUSH_VALUE, "terrain:bldg_weight", "5");
        assertEquals("building/hangar-castle-brian", session.snapshot().activeBrush().key());
        // A design's brush is no weight card: it keeps its design.
        send(session, Action.CHOOSE_BRUSH, "building", "building/frontier");
        send(session, Action.BRUSH_VALUE, "terrain:bldg_weight", "3");
        assertEquals("building/frontier", session.snapshot().activeBrush().key());
        assertEquals(3, session.snapshot().activeBrush().property("building").value());
    }

    @Test
    void pathsJoinInAllDirectionsAndSaveAndUndoAsOneStroke() throws Exception {
        for (int direction = 0; direction < 6; direction++) {
            var session = wallBrush();
            Coords next = CENTER.translated(direction), far = next.translated(direction);
            drag(session, CENTER, far); // Skipped hex is filled by the shared route drawing code.
            assertFalse(session.board().getHex(CENTER).containsTerrain(Terrains.BUILDING));
            var preview = session.preview(far, 0, 0, "ground");
            assertEquals(List.of(CENTER, next, far), preview.path());
            assertFalse(session.board().getHex(next).containsTerrain(Terrains.BUILDING), "Preview leaves the board untouched");
            session.finishStroke();
            assertEquals(1 << direction, exits(session, CENTER));
            assertEquals((1 << direction) | (1 << ((direction + 3) % 6)), exits(session, next));
            assertEquals(1 << ((direction + 3) % 6), exits(session, far));
            preview.after().forEach((at, hex) -> assertEquals(hex.getTerrain(Terrains.BUILDING).toString(),
                  session.board().getHex(at).getTerrain(Terrains.BUILDING).toString()));
            send(session, Action.UNDO, "", "");
            for (Coords at : List.of(CENTER, next, far)) { assertFalse(session.board().getHex(at).containsTerrain(Terrains.BUILDING)); }
            send(session, Action.REDO, "", "");
            Path file = directory.resolve("walls-" + direction + ".board2");
            session.save(file);
            var loaded = BoardFile.read(file);
            for (Coords at : List.of(CENTER, next, far)) {
                assertEquals(exits(session, at), loaded.getHex(at).getTerrain(Terrains.BUILDING).getExits());
                assertEquals(5, loaded.getHex(at).terrainLevel(Terrains.BUILDING));
            }
        }
    }

    @Test
    void retracingRemovesPendingHexesAndTurnsBeforeReleaseAndCancelDropsThePath() {
        var session = wallBrush();
        Coords next = CENTER.translated(0), far = next.translated(0), turn = next.translated(1);
        drag(session, CENTER, next, far, next, turn);
        var preview = session.preview(turn, 0, 0, "ground");
        assertEquals(List.of(CENTER, next, turn), preview.path());
        assertFalse(preview.after().containsKey(far));
        session.finishStroke();
        assertFalse(session.board().getHex(far).containsTerrain(Terrains.BUILDING));
        assertEquals(10, exits(session, next));
        send(session, Action.UNDO, "", "");
        assertFalse(session.board().getHex(turn).containsTerrain(Terrains.BUILDING));
        drag(session, CENTER, next, far);
        session.cancelStroke();
        session.finishStroke();
        assertFalse(session.board().getHex(CENTER).containsTerrain(Terrains.BUILDING));
    }

    @Test
    void branchingRetainsExistingConnectionsButDoesNotJoinNearbyParallelWalls() {
        var session = wallBrush();
        Coords north = CENTER.translated(0), far = north.translated(0), beside = CENTER.translated(1);
        session.board().setHex(north, wall(1));
        session.board().setHex(far, wall(8));
        session.board().setHex(beside, wall(0));
        drag(session, CENTER, north); session.finishStroke();
        assertEquals(1, exits(session, CENTER));
        assertEquals(9, exits(session, north));
        assertEquals(0, exits(session, beside));
        send(session, Action.TOOL, "", "ERASE");
        var preview = session.preview(CENTER, 0, 0, "ground");
        assertEquals(1, preview.after().get(north).getTerrain(Terrains.BUILDING).getExits());
        assertEquals(9, exits(session, north));
        drag(session, CENTER); session.finishStroke();
        assertFalse(session.board().getHex(CENTER).containsTerrain(Terrains.BUILDING));
        assertEquals(1, exits(session, north));
        assertEquals(8, exits(session, far));
        send(session, Action.UNDO, "", "");
        assertEquals(1, exits(session, CENTER));
        assertEquals(9, exits(session, north));
    }

    @Test
    void wallStrokeCannotOverwriteBuildingsOrLeaveDanglingExitsThroughThem() {
        var session = wallBrush();
        Coords next = CENTER.translated(0), far = next.translated(0);
        session.board().setHex(next, new Hex(0, "building:4:0;bldg_cf:120;bldg_elev:2", ""));
        drag(session, CENTER, far); session.finishStroke();
        assertEquals(0, exits(session, CENTER));
        assertEquals(0, exits(session, far));
        assertEquals(4, session.board().getHex(next).terrainLevel(Terrains.BUILDING));
        assertEquals(120, session.board().getHex(next).terrainLevel(Terrains.BLDG_CF));
    }

    @Test
    void unrelatedEditsDoNotReconnectAuthoredWallExits() {
        var session = wallBrush();
        Coords next = CENTER.translated(0);
        session.board().setHex(CENTER, wall(0));
        session.board().setHex(next, wall(0));
        session.adjustElevation(CENTER, 1);
        session.finishStroke();
        assertEquals(0, exits(session, CENTER));
        assertEquals(0, exits(session, next));
    }

    private static Hex wall(int exits) {
        return new Hex(0, "building:5:" + exits + ";bldg_cf:120;bldg_elev:2", "");
    }

    private static BoardEditorSession wallBrush() {
        var session = new BoardEditorSession();
        send(session, Action.CHOOSE_BRUSH, "building", "wall");
        return session;
    }

    private static void drag(BoardEditorSession session, Coords... path) {
        for (int i = 0; i < path.length; i++) { session.pointer(path[i], 0, 0, i != 0); }
    }

    private static int exits(BoardEditorSession session, Coords at) {
        return session.board().getHex(at).getTerrain(Terrains.BUILDING).getExits();
    }

    private static void send(BoardEditorSession session, Action action, String target, String value) {
        session.command(new Command(action, target, value), null);
    }
}
