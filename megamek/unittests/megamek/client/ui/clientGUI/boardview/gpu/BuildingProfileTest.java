/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.TilesetScenery;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BuildingProfileTest {
    @Test
    void profileIsPerHexUndoableAndOnlyNonDefaultIsSaved(@TempDir Path directory) throws Exception {
        var board = Board.createEmptyBoard(5, 5);
        Coords tall = new Coords(2, 2), low = tall.translated(0), other = low.translated(0);
        board.setHex(tall, building(0, 9, 1));
        board.setHex(low, building(0, 1, 9));
        board.setHex(other, building(0, 4, 8));
        var editor = new BoardEditorSession(); editor.open(board, null);
        editor.command(new Command(Action.SELECT_AT, "2,2", ""), null);
        assertTrue(editor.snapshot().buildingProfileAvailable());
        assertFalse(HexAppearance.steppedBuilding(editor.snapshot().appearance()));
        assertFalse(BoardFile.hexNode(editor.board().getHex(tall)).toString().contains("profile"));

        editor.command(new Command(Action.BUILDING_PROFILE, "stepped"), null);
        assertTrue(HexAppearance.steppedBuilding(editor.board().getHex(tall).getAppearance()));
        assertFalse(HexAppearance.steppedBuilding(editor.board().getHex(other).getAppearance()));
        assertFalse(HexAppearance.steppedBuilding(editor.board().getHex(low).getAppearance()));
        editor.command(new Command(Action.UNDO), null);
        assertFalse(HexAppearance.steppedBuilding(editor.snapshot().appearance()));
        editor.command(new Command(Action.REDO), null);
        Path file = directory.resolve("profiles.board2"); editor.save(file); editor.open(file);
        var saved = new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(file));
        for (var entry : saved.path("hexes")) {
            boolean selected = entry.path("at").get(0).asInt() == 3 && entry.path("at").get(1).asInt() == 3;
            assertEquals(selected ? "stepped" : "", entry.path("components").path("building").path("appearance").path("profile").asText());
        }
        assertTrue(HexAppearance.steppedBuilding(editor.board().getHex(tall).getAppearance()));
        assertFalse(HexAppearance.steppedBuilding(editor.board().getHex(other).getAppearance()));

        editor.command(new Command(Action.SELECT_AT, "2,2", ""), null);
        editor.command(new Command(Action.BUILDING_PROFILE, "straight"), null);
        editor.save(file);
        assertFalse(Files.readString(file).contains("\"profile\""));
        var reloaded = BoardFile.read(file);
        assertFalse(HexAppearance.steppedBuilding(reloaded.getHex(tall).getAppearance()));
        assertFalse(BoardFile.hexNode(reloaded.getHex(tall)).toString().contains("profile"));
        editor.command(new Command(Action.UNDO), null);
        assertTrue(HexAppearance.steppedBuilding(editor.snapshot().appearance()));
    }

    @ParameterizedTest
    @ValueSource(strings = { "selection", "board", "erase" })
    void removingBuildingClearsItsProfileAndUndoRestoresIt(String removal, @TempDir Path directory) throws Exception {
        Coords at = new Coords(2, 2);
        var board = Board.createEmptyBoard(5, 5);
        board.setHex(at, building(0, 4, 1));
        board.setHex(at.translated(0), building(0, 1, 8));
        var editor = new BoardEditorSession(); editor.open(board, null);
        editor.command(new Command(Action.SELECT_AT, "2,2", ""), null);
        editor.command(new Command(Action.BUILDING_PROFILE, "stepped"), null);
        assertTrue(HexAppearance.steppedBuilding(editor.board().getHex(at).getAppearance()));

        if (removal.equals("erase")) {
            editor.command(new Command(Action.CHOOSE_BRUSH, "building", ""), null);
            editor.command(new Command(Action.TOOL, "ERASE"), null);
            editor.pointer(at, 0, 0, false); editor.finishStroke();
        } else {
            editor.command(new Command(Action.REMOVE_COMPONENT, removal, "building"), null);
        }
        assertFalse(editor.board().getHex(at).containsTerrain(Terrains.BUILDING));
        assertFalse(editor.board().getHex(at).getAppearance().containsKey("buildingProfile"));
        editor.command(new Command(Action.UNDO), null);
        assertTrue(editor.board().getHex(at).containsTerrain(Terrains.BUILDING));
        assertTrue(HexAppearance.steppedBuilding(editor.board().getHex(at).getAppearance()));
        editor.command(new Command(Action.REDO), null);
        assertFalse(editor.board().getHex(at).containsTerrain(Terrains.BUILDING));
        assertFalse(editor.board().getHex(at).getAppearance().containsKey("buildingProfile"));

        Path file = directory.resolve("removed.board2"); editor.save(file);
        assertFalse(Files.readString(file).contains("\"profile\""));
        assertFalse(BoardFile.read(file).getHex(at).getAppearance().containsKey("buildingProfile"));
        editor.command(new Command(Action.SELECT_AT, "2,2", ""), null);
        editor.command(new Command(Action.ADD_COMPONENT, "building"), null);
        assertTrue(editor.board().getHex(at).containsTerrain(Terrains.BUILDING));
        assertFalse(HexAppearance.steppedBuilding(editor.board().getHex(at).getAppearance()),
              "A replacement building starts with Keep footprint");
        editor.save(file);
        assertFalse(Files.readString(file).contains("\"profile\""));
        assertFalse(HexAppearance.steppedBuilding(BoardFile.read(file).getHex(at).getAppearance()));
    }

    @Test
    void choiceAppearsOnlyWhereAConnectedNeighbourMakesUpperFloorsDifferent() {
        Coords at = new Coords(3, 3);
        for (int direction = 0; direction < 6; direction++) {
            Coords next = at.translated(direction);
            Hex tall = building(0, 4, 1 << direction);
            int back = 1 << (direction + 3) % 6;
            Hex low = building(0, 1, back);
            var connected = Map.of(at, tall, next, low);
            assertTrue(TilesetScenery.buildingProfileAvailable(tall, at, connected::get));
            assertFalse(TilesetScenery.buildingProfileAvailable(low, next, connected::get));
            assertFalse(TilesetScenery.buildingProfileAvailable(tall, at, Map.of(at, tall)::get));
            for (Hex neighbour : new Hex[] { building(0, 4, back), building(0, 1, 0), building(-1, 1, back),
                  building(4, 1, back), building(1, 3, back) }) {
                assertFalse(TilesetScenery.buildingProfileAvailable(tall, at, Map.of(at, tall, next, neighbour)::get));
            }
            assertTrue(TilesetScenery.buildingProfileAvailable(tall, at, Map.of(at, tall, next, building(1, 2, back))::get));
            tall.setAppearance(Map.of("building", new HexAppearance("building/glass-dome", null, null, null)));
            assertFalse(TilesetScenery.buildingProfileAvailable(tall, at, connected::get));
        }
    }

    private static Hex building(int elevation, int height, int exits) {
        return new Hex(elevation, "building:1:" + exits + ";bldg_elev:" + height + ";bldg_cf:15", "grass");
    }
}
