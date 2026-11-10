/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.boardeditor;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.TilesetScenery;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoardEditorBuildingTest {
    @Test
    void paintedBendsConnectOnlyAcrossCrossedBordersAndUndoAsOneStroke(@TempDir Path directory) throws Exception {
        for (String design : List.of("", "building/lunar-habitat")) {
            var editor = new BoardEditorSession();
            editor.open(Board.createEmptyBoard(10, 10), null);
            editor.command(new Command(Action.CHOOSE_BRUSH, "building", design), null);
            var path = new java.util.ArrayList<Coords>();
            path.add(new Coords(3, 5));
            // An S with adjacent arms: adjacent hexes that the stroke never crossed must stay separate.
            for (int direction : new int[] { 0, 0, 1, 2, 3, 2, 1, 0, 0 }) { path.add(path.getLast().translated(direction)); }
            for (int i = 0; i < path.size(); i++) { editor.pointer(path.get(i), 0, 0, i != 0); }
            var preview = editor.preview(path.getLast(), 0, 0, "ground");
            assertEquals(path, preview.path());
            editor.finishStroke();
            Path file = directory.resolve("stroke.board2"); editor.save(file);
            var loaded = BoardFile.read(file);
            for (int i = 0; i < path.size(); i++) {
                Coords at = path.get(i);
                int expected = (i == 0 ? 0 : 1 << at.direction(path.get(i - 1)))
                      | (i + 1 == path.size() ? 0 : 1 << at.direction(path.get(i + 1)));
                assertEquals(expected, editor.board().getHex(at).getTerrain(Terrains.BUILDING).getExits(), at.toString());
                assertEquals(expected, loaded.getHex(at).getTerrain(Terrains.BUILDING).getExits());
                assertEquals(expected, preview.after().get(at).getTerrain(Terrains.BUILDING).getExits());
            }
            editor.command(new Command(Action.UNDO), null);
            for (Coords at : path) { assertFalse(editor.board().getHex(at).containsTerrain(Terrains.BUILDING)); }
            editor.command(new Command(Action.REDO), null);
            assertTrue(editor.board().getHex(path.getFirst()).containsTerrain(Terrains.BUILDING));
        }
    }

    @Test
    void paddingSurvivesAppearanceEditsAndSaveAndDefaultsToOmitted(@TempDir Path directory) throws Exception {
        var editor = new BoardEditorSession();
        editor.open(Board.createEmptyBoard(5, 5), null);
        Coords at = new Coords(2, 2);
        editor.command(new Command(Action.CHOOSE_BRUSH, "building", ""), null);
        editor.pointer(at, 0, 0, false); editor.finishStroke();
        editor.command(new Command(Action.BUILDING_PADDING, "20"), null);
        editor.command(new Command(Action.DESIGN_ROTATION, "building", "30"), null);
        editor.command(new Command(Action.BUILDING_COLOUR, "#72949b"), null);
        Path file = directory.resolve("padding.board2"); editor.save(file); editor.open(file);
        assertEquals(20, HexAppearance.padding(editor.board().getHex(at).getAppearance()));
        editor.command(new Command(Action.SELECT_AT, "2,2", ""), null);
        editor.command(new Command(Action.BUILDING_PADDING, "0"), null);
        assertEquals(0, HexAppearance.padding(editor.board().getHex(at).getAppearance()));
        assertFalse(BoardFile.hexNode(editor.board().getHex(at)).toString().contains("padding"));
        editor.command(new Command(Action.UNDO), null);
        assertEquals(20, HexAppearance.padding(editor.board().getHex(at).getAppearance()));
    }

    @Test
    void everyGenericWeightAndClassUsesTheSameModularFamilyWhenIsolatedOrConnected() {
        for (int weight = 1; weight <= 4; weight++) {
            for (int buildingClass = 0; buildingClass <= 3; buildingClass++) {
                String expected = null;
                for (int height : List.of(1, 2, 4)) {
                    for (int connections : List.of(0, 9)) {
                        Hex hex = new Hex(0, "building:" + weight + ":" + connections + ";bldg_class:" + buildingClass
                              + ";bldg_elev:" + height + ";bldg_cf:90;bldg_basement_type:1", "");
                        String model = TilesetScenery.structureModels(hex).get(Terrains.BUILDING);
                        assertTrue(TilesetScenery.generatedKit(model), model);
                        assertTrue(TilesetScenery.customBuildingFile(model).isFile(), model);
                        if (expected == null) { expected = model; } else { assertEquals(expected, model); }
                    }
                }
            }
        }
    }

    @Test
    void genericRotationAndConnectedTintSurviveSavingAndUndo(@TempDir Path directory) throws Exception {
        Coords first = new Coords(2, 2), second = first.translated(0);
        var editor = new BoardEditorSession();
        editor.open(Board.createEmptyBoard(5, 5), null);
        editor.command(new Command(Action.CHOOSE_BRUSH, "building", ""), null);
        editor.command(new Command(Action.BRUSH_VALUE, "rotation:building", "75"), null);
        editor.pointer(first, 0, 0, false); editor.finishStroke();
        Path file = directory.resolve("generic.board2"); editor.save(file); editor.open(file);
        assertEquals(75, editor.board().getHex(first).getAppearance().get("building").rotation());
        editor.command(new Command(Action.CHOOSE_BRUSH, "building", ""), null);
        editor.pointer(second, 0, 0, false); editor.finishStroke();
        editor.command(new Command(Action.EDGE, "building", "3"), null);
        editor.command(new Command(Action.BUILDING_COLOUR, "#72949b"), null);
        for (Coords cell : List.of(first, second)) {
            var design = editor.board().getHex(cell).getAppearance().get("building");
            assertNull(design.rotation()); assertEquals("#72949b", design.colours().slot(0));
        }
        editor.save(file); editor.open(file);
        for (Coords cell : List.of(first, second)) {
            assertEquals("#72949b", editor.board().getHex(cell).getAppearance().get("building").colours().slot(0));
        }
        editor.command(new Command(Action.SELECT_AT, "2,2", ""), null);
        editor.command(new Command(Action.BUILDING_COLOUR, ""), null);
        assertFalse(editor.board().getHex(first).getAppearance().containsKey("building"));
        editor.command(new Command(Action.UNDO), null);
        assertEquals("#72949b", editor.board().getHex(first).getAppearance().get("building").colours().slot(0));
    }

    @Test
    void automaticConnectionsStillExtendTheExistingBuildingsAppearance() {
        Coords first = new Coords(2, 2), second = first.translated(0);
        var editor = new BoardEditorSession();
        editor.open(Board.createEmptyBoard(5, 5), null);
        editor.command(new Command(Action.CHOOSE_BRUSH, "building", "building/lunar-habitat"), null);
        editor.pointer(first, 0, 0, false);
        editor.finishStroke();
        deriveExits(editor, first);
        editor.command(new Command(Action.FOOTPRINT, "square"), null);
        editor.command(new Command(Action.KIT_VARIANT, "parapet"), null);
        editor.pointer(second, 0, 0, false);
        editor.finishStroke();
        for (Coords at : List.of(first, second)) {
            var appearance = editor.board().getHex(at).getAppearance();
            assertEquals(HexAppearance.Footprint.SQUARE, HexAppearance.footprint(appearance));
            assertEquals("parapet", HexAppearance.kitVariant(appearance),
                  "A derived exit change must not count as repainting the existing neighbour");
        }
        editor.command(new Command(Action.UNDO), null);
        assertFalse(editor.board().getHex(second).containsTerrain(Terrains.BUILDING));
        assertEquals(0, editor.board().getHex(first).getTerrain(Terrains.BUILDING).getExits());
    }

    @Test
    void paintedLightBuildingsKeepTheirConnectionsAndModelsAfterReopening(@TempDir Path directory) throws Exception {
        Coords first = new Coords(2, 2);
        for (int direction = 0; direction < 6; direction++) {
            Coords second = first.translated(direction), separate = second.translated(direction);
            var editor = new BoardEditorSession();
            editor.open(Board.createEmptyBoard(5, 5), null);
            editor.command(new Command(Action.CHOOSE_BRUSH, "building", ""), null);
            editor.command(new Command(Action.BRUSH_VALUE, "terrain:bldg_elev", "2"), null);
            editor.pointer(first, 0, 0, false);
            editor.pointer(second, 0, 0, true);
            editor.finishStroke();
            for (Coords at : List.of(first, second)) { deriveExits(editor, at); }
            // A deliberately separate neighbouring building must remain separate after loading.
            editor.board().setHex(separate, new Hex(0, "building:1:0;bldg_cf:15;bldg_elev:2;bldg_basement_type:1", ""));
            Map<Coords, Integer> exits = Map.of(first, 1 << direction, second, 1 << ((direction + 3) % 6), separate, 0);
            // The automatic neighbour may now point at the separate building; pin this edge off as an authored edit.
            editor.command(new Command(Action.SELECT_AT, second.getX() + "," + second.getY(), ""), null);
            if (editor.board().getHex(second).containsTerrainExit(Terrains.BUILDING, direction)) {
                editor.command(new Command(Action.EDGE, "building", Integer.toString(direction)), null);
            }
            Map<Coords, Map<Integer, String>> models = Map.of(
                  first, TilesetScenery.structureModels(editor.board().getHex(first)),
                  second, TilesetScenery.structureModels(editor.board().getHex(second)),
                  separate, TilesetScenery.structureModels(editor.board().getHex(separate)));
            assertTrue(models.get(first).get(Terrains.BUILDING).contains("building-light"));
            assertFalse(editor.board().getHex(first).getTerrain(Terrains.BUILDING).hasExitsSpecified(),
                  "Exercise calculated connections, which the native writer used to omit");
            for (Coords at : List.of(first, second, separate)) {
                assertEquals(exits.get(at).intValue(), editor.board().getHex(at).getTerrain(Terrains.BUILDING).getExits());
            }

            Path file = directory.resolve("light-" + direction + ".board2");
            editor.save(file);
            editor.open(file);
            for (Coords at : List.of(first, second, separate)) {
                Hex hex = editor.board().getHex(at);
                assertEquals(exits.get(at).intValue(), hex.getTerrain(Terrains.BUILDING).getExits());
                assertEquals(models.get(at), TilesetScenery.structureModels(hex), "Reopening must select the same artwork");
                assertEquals(2, hex.terrainLevel(Terrains.BLDG_ELEV));
            }
            assertSame(editor.board().getBuildingAt(first), editor.board().getBuildingAt(second));
            assertNotSame(editor.board().getBuildingAt(first), editor.board().getBuildingAt(separate));
            editor.save(file);
            assertEquals(models.get(first), TilesetScenery.structureModels(BoardFile.read(file).getHex(first)));
        }
    }

    /** Gives the building at {@code at} derived exits, as a building of an unconverted legacy file has. */
    private static void deriveExits(BoardEditorSession editor, Coords at) {
        Hex hex = editor.board().getHex(at).duplicate();
        hex.addTerrain(new Terrain(Terrains.BUILDING, hex.terrainLevel(Terrains.BUILDING)));
        editor.board().setHex(at, hex);
    }
}
