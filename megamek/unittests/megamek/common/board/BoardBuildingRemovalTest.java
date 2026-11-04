/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Editor deletion and native board serialization must agree on ownership of authored building members. */
class BoardBuildingRemovalTest {
    @Test
    void deletingAMemberRemovesItsPlacementAndFollowersInOneSavedUndoStep(@TempDir Path dir) throws Exception {
        for (String removal : List.of("component", "board", "terrain", "eraser", "selection")) {
            var editor = new BoardEditorSession(); editor.open(Board.createEmptyBoard(9, 9), null);
            Coords anchor = new Coords(4, 4), follower = anchor.translated(1);
            editor.command(new Command(Action.ASSET, "buildings/debug/debug-authored"), null);
            editor.pointer(anchor, 0, 0, false); editor.finishStroke();
            var building = editor.board().getHex(anchor).getDecorations().getFirst();
            var prop = new BoardDecoration("retained-car", "prop", "scenery/vehicles/car", null, 0, 0, 0, false, 1,
                  BoardDecoration.Placement.ground(), 0);
            editor.board().getHex(anchor).setDecorations(List.of(building, prop));
            var before = new LinkedHashMap<Coords, Object>();
            for (Coords at : List.of(anchor, follower)) {
                var hex = editor.board().getHex(at); assertNotNull(hex.getBuildingReference());
                hex.addTerrain(new Terrain(Terrains.ROUGH, 1));
                hex.setAppearance(Map.of("building", new HexAppearance("building/lunar-habitat", null, null, null),
                      "footprint", HexAppearance.SQUARE, "kitVariant", HexAppearance.ofKitVariant("default"),
                      "buildingProfile", HexAppearance.STEPPED_BUILDING));
                before.put(at, BoardFile.hexNode(hex));
            }
            editor.command(new Command(Action.SELECT_AT, follower.getX() + "," + follower.getY(), ""), null);
            switch (removal) {
                case "component", "board" -> editor.command(new Command(Action.REMOVE_COMPONENT,
                      removal.equals("board") ? "board" : "", "building"), null);
                case "terrain" -> editor.command(new Command(Action.REMOVE_TERRAIN, "building"), null);
                case "eraser" -> {
                    editor.command(new Command(Action.CHOOSE_BRUSH, "building", ""), null);
                    editor.command(new Command(Action.TOOL, "ERASE"), null);
                    editor.pointer(follower, 0, 0, false); editor.finishStroke();
                }
                case "selection" -> editor.command(new Command(Action.DELETE_SELECTION), null);
                default -> fail(removal);
            }
            for (Coords at : before.keySet()) {
                var hex = editor.board().getHex(at); assertNull(hex.getBuildingReference(), removal);
                for (int terrain : List.of(Terrains.BUILDING, Terrains.BLDG_ELEV, Terrains.BLDG_CF, Terrains.BLDG_INTERIORS,
                      Terrains.BLDG_BASEMENT_TYPE, Terrains.BLDG_CLASS, Terrains.BLDG_ARMOR, Terrains.BLDG_BASE_COLLAPSED)) {
                    assertFalse(hex.containsTerrain(terrain), removal + ": " + terrain);
                }
                assertTrue(hex.getAppearance().isEmpty(), removal);
                if (!removal.equals("selection") || at.equals(anchor)) {
                    assertEquals(1, hex.terrainLevel(Terrains.ROUGH), removal);
                }
            }
            assertEquals(List.of(prop), editor.board().getHex(anchor).getDecorations(), removal);
            Path file = dir.resolve(removal + ".board2"); editor.save(file);
            assertFalse(Files.readString(file).contains(building.id()));
            Board loaded = BoardFile.read(file);
            var retained = loaded.getHex(anchor).getDecorations(); assertEquals(1, retained.size());
            assertEquals(prop.id(), retained.getFirst().id()); assertEquals(prop.asset(), retained.getFirst().asset());
            for (Coords at : before.keySet()) {
                assertNull(loaded.getHex(at).getBuildingReference()); assertTrue(loaded.getHex(at).getAppearance().isEmpty());
            }
            editor.command(new Command(Action.UNDO), null);
            before.forEach((at, node) -> assertEquals(node, BoardFile.hexNode(editor.board().getHex(at)), removal));
            editor.command(new Command(Action.REDO), null);
            assertEquals(List.of(prop), editor.board().getHex(anchor).getDecorations(), removal);
            for (Coords at : before.keySet()) { assertNull(editor.board().getHex(at).getBuildingReference(), removal); }
        }
    }

    @Test
    void staleMembersCanBeErased(@TempDir Path dir) throws Exception {
        Board board = BoardFile.readNative("""
              {"format":"megamek-board","version":3,"size":{"width":2,"height":1},"hexes":[
               {"at":[1,1],"elevation":0,"decorations":[{"id":"stale-building","kind":"prop",
                "asset":"buildings/debug/debug-authored","position":[0.2,0.1],"placement":{"mode":"surface",
                "receiver":{"terrain":"ground","surface":"top"},"offset":0}}]},
               {"at":[2,1],"elevation":0,"structure":{"id":"stale-building","anchor":[1,1]}}]}
              """);
        var editor = new BoardEditorSession(); editor.open(board, null);
        editor.command(new Command(Action.SELECT_AT, "1,0", ""), null);
        editor.command(new Command(Action.REMOVE_COMPONENT, "", "building"), null);
        assertTrue(editor.board().getHex(0, 0).getDecorations().isEmpty());
        assertNull(editor.board().getHex(1, 0).getBuildingReference());
        Path file = dir.resolve("repaired.board2"); editor.save(file);
        assertFalse(Files.readString(file).contains("stale-building"));
        editor.command(new Command(Action.UNDO), null);
        assertEquals("stale-building", editor.board().getHex(0, 0).getDecorations().getFirst().id());
        assertNotNull(editor.board().getHex(1, 0).getBuildingReference());
    }
}
