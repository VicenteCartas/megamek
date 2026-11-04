/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.boardeditor;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.BuildingInteriors;
import megamek.common.board.BuildingVolumes;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import megamek.common.util.BoardUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoardEditorAuthoredBuildingTest {
    @Test void scaleAndAxisStretchRebuildGameplayAndSurviveUndoAndReload(@TempDir Path dir) throws Exception {
        var editor = new BoardEditorSession(); editor.open(Board.createEmptyBoard(40, 30), null);
        Coords anchor = new Coords(20, 15);
        editor.command(new Command(Action.ASSET, "buildings/authored/hpg-headquarters"), null);
        editor.pointer(anchor, 0, 0, false); editor.finishStroke();
        var object = editor.board().getHex(anchor).getDecorations().getFirst();
        var original = members(editor.board(), object.id());
        editor.command(new Command(Action.SELECT_AT, "20,15", ""), null);
        editor.command(new Command(Action.SELECT_OBJECT, object.id()), null);
        editor.command(new Command(Action.OBJECT_VALUE, "scale", "1.25"), null);
        var enlarged = members(editor.board(), object.id());
        assertTrue(enlarged.size() > original.size());
        assertEquals(4, enlarged.values().stream().mapToInt(Integer::intValue).max().orElseThrow());

        editor.command(new Command(Action.OBJECT_VALUE, "stretchX", "1.5"), null);
        editor.command(new Command(Action.OBJECT_VALUE, "stretchY", ".8"), null);
        editor.command(new Command(Action.OBJECT_VALUE, "stretchZ", "2"), null);
        var stretched = members(editor.board(), object.id());
        assertNotEquals(enlarged.keySet(), stretched.keySet());
        // The dish remains a prop: only the three-level structural roof scales to 7.5 levels.
        assertEquals(8, stretched.values().stream().mapToInt(Integer::intValue).max().orElseThrow());
        editor.save(dir.resolve("scaled-hpg.board2"));
        Board loaded = BoardFile.read(dir.resolve("scaled-hpg.board2"));
        var saved = loaded.getHex(anchor).getDecorations().getFirst();
        assertEquals(1.25, saved.scale());
        assertEquals(1.5, saved.stretch().x());
        assertEquals(.8, saved.stretch().y());
        assertEquals(2, saved.stretch().z());
        assertEquals(stretched, members(loaded, object.id()));
        for (Coords cell : original.keySet()) {
            if (!stretched.containsKey(cell)) { assertNull(loaded.getHex(cell).getBuildingReference()); }
        }
        for (int i = 0; i < 3; i++) { editor.command(new Command(Action.UNDO), null); }
        assertEquals(enlarged, members(editor.board(), object.id()));
        editor.command(new Command(Action.UNDO), null);
        assertEquals(original, members(editor.board(), object.id()));
    }

    @Test void authoredBrushPlacesItsScaleAndAxisStretch() {
        var editor = new BoardEditorSession(); editor.open(Board.createEmptyBoard(36, 30), null);
        Coords anchor = new Coords(18, 15);
        editor.command(new Command(Action.ASSET, "buildings/authored/hpg-headquarters"), null);
        editor.command(new Command(Action.BRUSH_VALUE, "object:rotation", "90"), null);
        editor.command(new Command(Action.BRUSH_VALUE, "object:scale", "1.25"), null);
        editor.command(new Command(Action.BRUSH_VALUE, "object:stretchX", "1.5"), null);
        editor.command(new Command(Action.BRUSH_VALUE, "object:stretchY", ".8"), null);
        editor.pointer(anchor, 0, 0, false); editor.finishStroke();
        var object = editor.board().getHex(anchor).getDecorations().getFirst();
        assertEquals(90, object.rotation());
        assertEquals(1.25, object.scale());
        assertEquals(1.5, object.stretch().x());
        assertEquals(.8, object.stretch().y());
        assertEquals(4, members(editor.board(), object.id()).values().stream().mapToInt(Integer::intValue).max().orElseThrow());
    }

    @Test void belowHalfHexCoverageKeepsPlacementWithoutGameplayAndCanBeEnlargedAgain(@TempDir Path dir) throws Exception {
        var editor = new BoardEditorSession(); editor.open(Board.createEmptyBoard(32, 24), null);
        Coords anchor = new Coords(16, 12);
        editor.command(new Command(Action.ASSET, "buildings/authored/hpg-headquarters"), null);
        editor.pointer(anchor, 0, 0, false); editor.finishStroke();
        var original = editor.board().getHex(anchor).getDecorations().getFirst();
        var covered = members(editor.board(), original.id());
        assertFalse(covered.isEmpty());
        editor.command(new Command(Action.SELECT_AT, "16,12", ""), null);
        editor.command(new Command(Action.SELECT_OBJECT, original.id()), null);
        editor.command(new Command(Action.OBJECT_VALUE, "scale", ".05"), null);
        assertTrue(members(editor.board(), original.id()).isEmpty());
        for (Coords at : covered.keySet()) {
            assertFalse(editor.board().getHex(at).containsTerrain(Terrains.BUILDING));
            assertFalse(editor.board().getHex(at).containsTerrain(Terrains.BLDG_ELEV));
        }
        editor.save(dir.resolve("prop-hpg.board2"));
        Board loaded = BoardFile.read(dir.resolve("prop-hpg.board2"));
        assertEquals(.05, loaded.getHex(anchor).getDecorations().getFirst().scale());
        assertTrue(members(loaded, original.id()).isEmpty());
        editor.command(new Command(Action.UNDO), null);
        assertEquals(covered, members(editor.board(), original.id()));
        editor.command(new Command(Action.REDO), null);
        assertTrue(members(editor.board(), original.id()).isEmpty());
        editor.command(new Command(Action.OBJECT_VALUE, "scale", "1"), null);
        assertEquals(covered, members(editor.board(), original.id()));
    }

    @Test void placementRotationPrecisionMoveUndoAndReloadKeepOneInstanceAndItsGameplay(@TempDir Path dir) throws Exception {
        var editor = new BoardEditorSession(); editor.open(Board.createEmptyBoard(18, 18), null);
        Coords anchor = new Coords(7, 7);
        editor.command(new Command(Action.ASSET, "buildings/authored/hpg-headquarters"), null);
        editor.pointer(anchor, 0, 0, false); editor.finishStroke();
        var placed = editor.board().getHex(anchor).getDecorations().getFirst();
        var original = members(editor.board(), placed.id());
        assertTrue(original.size() >= 4, original::toString);
        assertTrue(original.values().stream().distinct().count() >= 2);
        assertEquals(original, BuildingVolumes.coverage(anchor, placed, 18, 18, editor.board()::getHex));
        editor.command(new Command(Action.SELECT_AT, "7,7", ""), null);
        editor.command(new Command(Action.SELECT_OBJECT, placed.id()), null);
        editor.command(new Command(Action.OBJECT_VALUE, "rotation", "37"), null);
        var rotated = members(editor.board(), placed.id());
        assertNotEquals(original, rotated);
        editor.command(new Command(Action.OBJECT_VALUE, "x", ".4"), null);
        var moved = members(editor.board(), placed.id());
        assertNotEquals(rotated, moved);
        editor.command(new Command(Action.BUILDING_INTERIORS, "2"), null);
        for (Coords cell : moved.keySet()) { assertEquals(BuildingInteriors.NO_FLOORS, BuildingInteriors.resolve(editor.board().getHex(cell))); }
        editor.save(dir.resolve("authored.board2"));
        Board loaded = BoardFile.read(dir.resolve("authored.board2"));
        assertEquals(moved, members(loaded, placed.id()));
        assertEquals(1, loaded.getHex(anchor).getDecorations().size());
        var saved = loaded.getHex(anchor).getDecorations().getFirst();
        assertEquals(37, saved.rotation()); assertEquals(.4, saved.x(), 1e-9);
        for (Coords cell : moved.keySet()) {
            assertEquals(anchor, loaded.getHex(cell).getBuildingReference().anchor());
            assertEquals(BuildingInteriors.NO_FLOORS, BuildingInteriors.resolve(loaded.getHex(cell)));
        }
        editor.command(new Command(Action.UNDO), null);
        editor.command(new Command(Action.UNDO), null);
        assertEquals(rotated, members(editor.board(), placed.id()));
        editor.command(new Command(Action.UNDO), null);
        assertEquals(original, members(editor.board(), placed.id()));
    }

    @Test void assembledHeightChangesAllMembersAndDeletingThePlacementClearsOnlyItsBuildingState() {
        var editor = new BoardEditorSession(); editor.open(Board.createEmptyBoard(14, 14), null);
        Coords anchor = new Coords(6, 6);
        editor.command(new Command(Action.ASSET, "buildings/authored/xenobiology-station"), null);
        editor.pointer(anchor, 0, 0, false); editor.finishStroke();
        var object = editor.board().getHex(anchor).getDecorations().getFirst();
        assertFalse(members(editor.board(), object.id()).isEmpty());
        editor.command(new Command(Action.SELECT_AT, "6,6", ""), null);
        editor.command(new Command(Action.SELECT_OBJECT, object.id()), null);
        editor.command(new Command(Action.OBJECT_VALUE, "levels", "5"), null);
        assertTrue(members(editor.board(), object.id()).values().stream().allMatch(h -> h == 5));
        editor.command(new Command(Action.REMOVE_OBJECT), null);
        assertTrue(members(editor.board(), object.id()).isEmpty());
        editor.command(new Command(Action.UNDO), null);
        assertTrue(members(editor.board(), object.id()).values().stream().allMatch(h -> h == 5));
    }

    @Test void cosmeticEditsKeepCollapsedSectionsAndMissingArtworkCanStillBeDeleted(@TempDir Path dir) throws Exception {
        var editor = new BoardEditorSession(); editor.open(Board.createEmptyBoard(14, 14), null);
        Coords anchor = new Coords(6, 6);
        editor.command(new Command(Action.ASSET, "buildings/authored/hpg-headquarters"), null);
        editor.pointer(anchor, 0, 0, false); editor.finishStroke();
        String id = editor.board().getHex(anchor).getDecorations().getFirst().id();
        editor.save(dir.resolve("intact.board2"));
        editor.open(BoardFile.read(dir.resolve("intact.board2")), null);
        editor.board().collapseBuilding(anchor);
        assertFalse(editor.board().getHex(anchor).containsTerrain(Terrains.BUILDING));
        editor.command(new Command(Action.SELECT_AT, "6,6", ""), null);
        editor.command(new Command(Action.SELECT_OBJECT, id), null);
        editor.command(new Command(Action.OBJECT_VALUE, "name", "Surviving headquarters"), null);
        assertFalse(editor.board().getHex(anchor).containsTerrain(Terrains.BUILDING));
        editor.save(dir.resolve("damaged.board2"));
        Board saved = BoardFile.read(dir.resolve("damaged.board2"));
        assertFalse(saved.getHex(anchor).containsTerrain(Terrains.BUILDING));
        assertNotNull(saved.getHex(anchor).getBuildingReference());
        var owner = editor.board().getHex(anchor);
        owner.setDecorations(owner.getDecorations().stream().map(o -> o.withAsset("buildings/missing/pack")).toList());
        editor.command(new Command(Action.OBJECT_VALUE, "x", "2"), null);
        assertEquals(0, editor.board().getHex(anchor).getDecorations().getFirst().x());
        editor.command(new Command(Action.OBJECT_VALUE, "rotation", "50"), null);
        assertEquals(0, editor.board().getHex(anchor).getDecorations().getFirst().rotation());
        var placement = editor.board().getHex(anchor).getDecorations().getFirst().placement();
        editor.command(new Command(Action.HEIGHT, id, "2"), null);
        assertEquals(placement, editor.board().getHex(anchor).getDecorations().getFirst().placement());
        editor.command(new Command(Action.OBJECT_VALUE, "name", "Missing artwork"), null);
        assertEquals("Missing artwork", editor.board().getHex(anchor).getDecorations().getFirst().name());
        assertEquals(anchor, editor.board().getHex(anchor).getBuildingReference().anchor());
        editor.command(new Command(Action.REMOVE_OBJECT), null);
        for (int x = 0; x < 14; x++) {
            for (int y = 0; y < 14; y++) {
                assertNull(editor.board().getHex(x, y).getBuildingReference());
                assertFalse(editor.board().getHex(x, y).containsTerrain(Terrains.BUILDING));
            }
        }
    }

    @Test void resizeCropAndRepeatedSheetsKeepReferencesAndPlacementPose() {
        var editor = new BoardEditorSession(); editor.open(Board.createEmptyBoard(14, 14), null);
        Coords anchor = new Coords(6, 6);
        editor.command(new Command(Action.ASSET, "buildings/authored/hpg-headquarters"), null);
        editor.pointer(anchor, 0, 0, false); editor.finishStroke();
        var board = editor.board();
        var original = board.getHex(anchor).getDecorations().getFirst();
        Board grown = BoardUtilities.resize(board, 2, 0, 2, 1);
        Coords moved = BoardUtilities.resized(anchor, 2, 1);
        for (Coords at : members(board, original.id()).keySet()) {
            assertEquals(moved, grown.getHex(BoardUtilities.resized(at, 2, 1)).getBuildingReference().anchor());
        }
        Board cropped = BoardUtilities.resize(board, 0, 0, 0, -7);
        var member = members(cropped, original.id()).keySet().iterator().next();
        var newAnchor = cropped.getHex(member).getBuildingReference().anchor();
        var retained = cropped.getHex(newAnchor).getDecorations().getFirst();
        var before = BuildingVolumes.roofs(BuildingVolumes.placed(BoardUtilities.resized(anchor, 0, -7), original, 0));
        var after = BuildingVolumes.roofs(BuildingVolumes.placed(newAnchor, retained, 0));
        assertEquals(before.keySet(), after.keySet());
        before.forEach((z, area) -> { var difference = new java.awt.geom.Area(area); difference.exclusiveOr(after.get(z)); assertTrue(BuildingVolumes.area(difference) < .01); });
        editor.open(cropped, null);
        editor.command(new Command(Action.SELECT_AT, newAnchor.getX() + "," + newAnchor.getY(), ""), null);
        editor.command(new Command(Action.SELECT_OBJECT, retained.id()), null);
        editor.command(new Command(Action.OBJECT_VALUE, "name", "Cropped headquarters"), null);
        editor.command(new Command(Action.OBJECT_VALUE, "colour0", "#72949b"), null);
        editor.command(new Command(Action.OBJECT_VALUE, "rotation", "11"), null);
        var edited = editor.board().getHex(newAnchor).getDecorations().getFirst();
        assertEquals("Cropped headquarters", edited.name());
        assertEquals("#72949b", edited.colours().slots().getFirst());
        assertEquals(11, edited.rotation());
        assertEquals(retained.x(), edited.x()); assertEquals(retained.y(), edited.y());
        Board combined = BoardUtilities.combine(14, 14, 2, 1, new Board[] { board, board }, 0);
        var second = combined.getHex(20, 6).getDecorations().getFirst();
        assertNotEquals(original.id(), second.id());
        for (Coords at : members(board, original.id()).keySet()) {
            var ref = combined.getHex(at.getX() + 14, at.getY()).getBuildingReference();
            assertEquals(second.id(), ref.id());
            assertEquals(new Coords(20, 6), ref.anchor());
        }
    }

    private static Map<Coords, Integer> members(Board board, String id) {
        Map<Coords, Integer> result = new LinkedHashMap<>();
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                Coords at = new Coords(x, y); var hex = board.getHex(at);
                if (hex.getBuildingReference() != null && hex.getBuildingReference().id().equals(id)) {
                    assertTrue(hex.containsTerrain(Terrains.BUILDING));
                    result.put(at, hex.terrainLevel(Terrains.BLDG_ELEV));
                }
            }
        }
        return result;
    }
}
