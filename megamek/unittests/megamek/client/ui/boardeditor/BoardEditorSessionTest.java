/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.boardeditor;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import megamek.client.ui.Messages;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.client.ui.clientGUI.RecentBoardList;
import megamek.common.Hex;
import megamek.common.IndustrialElevator;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.HexTile;
import megamek.common.loaders.MapSettings;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import megamek.utilities.BoardsTagger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoardEditorSessionTest {
    @TempDir Path directory;
    private final Coords at = new Coords(1, 1);

    @Test void changingATJunctionSurfacePreservesAutomaticPriorityAndExplicitSignals() throws Exception {
        var session = session();
        send(session, Action.ADD_COMPONENT, "", "road");
        for (int direction : new int[] { 0, 1, 3 }) { send(session, Action.EDGE, "road", Integer.toString(direction)); }
        send(session, Action.VARIANT, "road", "road/rounded");
        send(session, Action.TERRAIN, "road", "4");
        send(session, Action.VARIANT, "road", "road/material/gravel");
        Hex hex = session.board().getHex(at);
        assertEquals(11, hex.getTerrain(Terrains.ROAD).getExits());
        assertNull(hex.getAppearance().get("road").trafficControl(), "Surface edits do not turn automatic defaults into overrides");
        assertEquals(HexAppearance.TrafficControl.STOPS, HexAppearance.trafficControl(hex.getAppearance(), "road", 11));
        send(session, Action.TRAFFIC_CONTROL, "road", "gantry");
        send(session, Action.VARIANT, "road", "");
        send(session, Action.TERRAIN, "road", "1");
        Path file = directory.resolve("t-junction-controls.board2"); session.save(file);
        Hex loaded = BoardFile.read(file).getHex(at);
        assertEquals(HexAppearance.TrafficControl.GANTRY, HexAppearance.trafficControl(loaded.getAppearance(), "road", 11),
              "Explicit signals survive surface edits, design reset and saving");
    }

    @Test void junctionControlsSurviveRoadDesignChangesAndAreUndoableAndSaved() throws Exception {
        var session = session();
        send(session, Action.ADD_COMPONENT, "", "road");
        assertEquals(HexAppearance.TrafficControl.GANTRY, HexAppearance.trafficControl(session.board().getHex(at).getAppearance(), "road"));
        send(session, Action.TRAFFIC_CONTROL, "road", "signals");
        send(session, Action.VARIANT, "road", "road/rounded");
        send(session, Action.TERRAIN, "road", "4");
        send(session, Action.VARIANT, "road", "road/material/gravel");
        var road = session.board().getHex(at).getAppearance().get("road");
        assertEquals(HexAppearance.TrafficControl.SIGNALS, road.trafficControl());
        assertEquals("road/rounded", road.variant());
        assertEquals("gravel", road.material());
        send(session, Action.TRAFFIC_CONTROL, "road", "gantry");
        var gantry = HexAppearance.withTrafficControl(road, HexAppearance.TrafficControl.GANTRY);
        assertEquals(gantry, session.board().getHex(at).getAppearance().get("road"));
        send(session, Action.UNDO, "", "");
        assertEquals(road, session.board().getHex(at).getAppearance().get("road"));
        send(session, Action.REDO, "", "");
        send(session, Action.TRAFFIC_CONTROL, "road", "stops");
        assertEquals(HexAppearance.TrafficControl.STOPS, HexAppearance.trafficControl(session.board().getHex(at).getAppearance(), "road"));
        Path stopsFile = directory.resolve("road-stops.board2"); session.save(stopsFile);
        assertEquals(HexAppearance.TrafficControl.STOPS, HexAppearance.trafficControl(BoardFile.read(stopsFile).getHex(at).getAppearance(), "road"));
        send(session, Action.UNDO, "", "");
        assertEquals(gantry, session.board().getHex(at).getAppearance().get("road"));
        Path file = directory.resolve("road-controls.board2"); session.save(file);
        assertEquals(gantry, BoardFile.read(file).getHex(at).getAppearance().get("road"));
        send(session, Action.VARIANT, "road", "");
        assertEquals(HexAppearance.TrafficControl.GANTRY, HexAppearance.trafficControl(session.board().getHex(at).getAppearance(), "road"),
              "Resetting the road design keeps its independent junction controls");
        send(session, Action.REMOVE_COMPONENT, "", "road");
        assertFalse(session.board().getHex(at).getAppearance().containsKey("road"));
    }

    @Test void boardSettingsRemainUnsavedAcrossHexUndoAndSaveTogether() throws Exception {
        var session = session();
        Path file = directory.resolve("board-settings.board2");
        session.save(file);
        send(session, Action.ELEVATION, "", "2");
        session.board().addTag("Arena");
        send(session, Action.UNDO, "", "");
        assertTrue(session.dirty(), "Undoing a hex cannot hide unsaved board settings");
        session.save(file);
        assertFalse(session.dirty());
        var loaded = BoardFile.read(file);
        assertEquals(session.board().getTags(), loaded.getTags());
    }

    /**
     * A car-park Fluff value typed in Edit becomes its objects and decal in the same undo step, its lane road joining the
     * road beside it, and the token is gone (the document holds what its .board2 holds); Undo returns the hex as it was
     * before the value was typed, and another edit of the hex never converts it again.
     */
    @Test void aFluffValueTypedInEditBecomesObjectsWithoutItsTokenInOneUndoStep() {
        var session = session();
        Coords north = at.translated(0);
        session.board().setHex(north, new megamek.common.Hex(0, "road:1", ""));
        var hexBefore = BoardFile.hexNode(session.board().getHex(at));
        var northBefore = BoardFile.hexNode(session.board().getHex(north));
        send(session, Action.TERRAIN, "fluff", "5");
        var hex = session.board().getHex(at);
        assertFalse(hex.containsTerrain(Terrains.FLUFF), "The objects stand for the token, as in the saved .board2");
        assertTrue(hex.getDecorations().stream().anyMatch(o -> o.kind().equals("decal") && o.asset().startsWith("decal/car-park/")));
        assertTrue(hex.getDecorations().stream().anyMatch(o -> o.kind().equals("prop")), "The parked cars");
        assertTrue(hex.containsTerrain(Terrains.ROAD), "The car park's lane");
        assertNotEquals(northBefore, BoardFile.hexNode(session.board().getHex(north)), "The road beside it is written out");
        var converted = hex.getDecorations();

        send(session, Action.UNDO, "", "");
        assertEquals(hexBefore, BoardFile.hexNode(session.board().getHex(at)), "One undo step: no token, objects or lane");
        assertEquals(northBefore, BoardFile.hexNode(session.board().getHex(north)), "and the neighbour as it was");
        send(session, Action.REDO, "", "");
        assertEquals(converted, session.board().getHex(at).getDecorations());
        assertFalse(session.board().getHex(at).containsTerrain(Terrains.FLUFF), "Redo brings the objects, not the token");

        send(session, Action.ELEVATION, "", "1");
        send(session, Action.THEME, "", "snow");
        assertEquals(converted, session.board().getHex(at).getDecorations(), "Other edits never convert the hex again");
    }

    /**
     * The 2D Preview's BoardView follows the document only through the board and game events it listens to in a game, so
     * every kind of edit announces each hex it changes, after its last change and on the thread that edits (the EDT): a
     * paint stroke, a sculpt, an Edit-panel value, an object, Undo and Redo, an erase, the commit conversion of a typed
     * Fluff value with its lane road, and a replaced document (New, Open).
     */
    @Test void everyEditAnnouncesTheHexesItChangesAsTheBoardViewListensForThem() throws Exception {
        Path file = directory.resolve("announced.board2");
        var task = new java.util.concurrent.FutureTask<Void>(() -> {
            var session = session();
            Map<Coords, Object> seen = new java.util.HashMap<>();
            int[] replaced = { 0 };
            session.board().addBoardListener(new megamek.common.event.board.BoardListenerAdapter() {
                @Override public void boardChangedHex(megamek.common.event.board.BoardEvent event) {
                    assertTrue(javax.swing.SwingUtilities.isEventDispatchThread());
                    seen.put(event.getCoords(), BoardFile.hexNode(session.board().getHex(event.getCoords())));
                }
            });
            session.game().addGameListener(new megamek.common.event.GameListenerAdapter() {
                @Override public void gameBoardNew(megamek.common.event.board.GameBoardNewEvent event) {
                    assertSame(session.board(), event.getNewBoard());
                    replaced[0]++;
                }
            });
            java.util.function.BiConsumer<Coords, String> announced = (hex, edit) ->
                  assertEquals(BoardFile.hexNode(session.board().getHex(hex)), seen.get(hex), edit + " announces " + hex);
            Coords painted = new Coords(4, 4), sculpted = new Coords(6, 4), parked = new Coords(2, 2);
            send(session, Action.CHOOSE_BRUSH, "vegetation", "woods-1");
            session.pointer(painted, 0, 0, false); session.finishStroke();
            assertTrue(session.board().getHex(painted).containsTerrain(Terrains.WOODS));
            announced.accept(painted, "A paint stroke");
            send(session, Action.TOOL, "", "SCULPT");
            session.pointer(sculpted, 0, 0, false); session.finishStroke();
            assertEquals(1, session.board().getHex(sculpted).getLevel());
            announced.accept(sculpted, "A sculpt");
            send(session, Action.TOOL, "", "SELECT"); session.pointer(at, 0, 0, false); session.finishStroke();
            send(session, Action.TERRAIN, "water", "1");
            announced.accept(at, "An Edit-panel value");
            send(session, Action.ASSET, "", "scenery/vehicles/car");
            session.pointer(at, .1, -.1, false); session.finishStroke();
            assertFalse(session.board().getHex(at).getDecorations().isEmpty());
            announced.accept(at, "A placed object");
            send(session, Action.UNDO, "", "");
            announced.accept(at, "Undo");
            send(session, Action.REDO, "", "");
            announced.accept(at, "Redo");
            send(session, Action.TOOL, "", "ERASE");
            session.pointer(at, 0, 0, false); session.finishStroke();
            assertTrue(session.board().getHex(at).getDecorations().isEmpty(), "Erasing with the car brush removes the car");
            announced.accept(at, "An erase");
            session.board().setHex(parked.translated(0), new megamek.common.Hex(0, "road:1", ""));
            send(session, Action.TOOL, "", "SELECT"); session.pointer(parked, 0, 0, false); session.finishStroke();
            send(session, Action.TERRAIN, "fluff", "5");
            assertFalse(session.board().getHex(parked).containsTerrain(Terrains.FLUFF));
            assertFalse(session.board().getHex(parked).getDecorations().isEmpty());
            announced.accept(parked, "The commit conversion");
            announced.accept(parked.translated(0), "The commit conversion's lane road");
            session.save(file); // A clean document: New and Open ask nothing.
            send(session, Action.NEW, "", "8x8");
            assertEquals(1, replaced[0], "New is a new board of the game");
            session.open(file);
            assertEquals(2, replaced[0], "Open is a new board of the game");
            send(session, Action.RESIZE, "", "1,1,1,1");
            assertEquals(3, replaced[0], "A resize is a new board of the game");
            send(session, Action.UNDO, "", "");
            assertEquals(4, replaced[0], "So is its Undo");
            return null;
        });
        javax.swing.SwingUtilities.invokeAndWait(task);
        task.get();
    }

    private BoardEditorSession session() {
        var session = new BoardEditorSession(); session.pointer(at, 0, 0, false); return session;
    }
    private void send(BoardEditorSession session, Action action, String target, String value) {
        session.command(new Command(action, target, value), null);
    }

    @Test void contentsReorderingUpdatesPaintAndSurvivesUndoAndSave() throws Exception {
        var session = session();
        var ground = megamek.common.board.BoardDecoration.Placement.ground();
        var car = new megamek.common.board.BoardDecoration("car", "prop", "scenery/vehicles/car", null, 0, 0, 0, false, 1, ground, 0);
        var a = new megamek.common.board.BoardDecoration("a", "decal", "decal/damage/rubble-light-path", null, 0, 0, 0, false, 1, ground, 4);
        var b = new megamek.common.board.BoardDecoration("b", "decal", "decal/damage/rubble-light-path", null, .2, 0, 0, false, 1, ground, 2);
        var original = java.util.List.of(car, a, b);
        session.board().getHex(at).setDecorations(original);
        send(session, Action.SELECT_OBJECT, "", "car");
        send(session, Action.REORDER_OBJECT, "b", "before:a");
        var objects = session.snapshot().objects();
        assertEquals(java.util.List.of("car", "b", "a"), objects.stream().map(d -> d.id()).toList());
        assertTrue(objects.get(1).drawOrder() > objects.get(2).drawOrder());
        assertEquals("car", session.snapshot().object(), "Reordering preserves selection");
        assertEquals(b.placement(), objects.get(1).placement());
        assertEquals(b.x(), objects.get(1).x());
        send(session, Action.UNDO, "", "");
        assertEquals(original, session.board().getHex(at).getDecorations());
        send(session, Action.REDO, "", "");
        send(session, Action.REORDER_OBJECT, "car", "after:a");
        assertEquals("car", session.snapshot().objects().getLast().id());
        Path file = directory.resolve("contents-order.board2"); session.save(file);
        assertEquals(session.board().getHex(at).getDecorations(), BoardFile.read(file).getHex(at).getDecorations());
        send(session, Action.SELECT_OBJECT, "", "a");
        send(session, Action.OBJECT_VALUE, "order", "13");
        assertEquals("a", session.snapshot().objects().getFirst().id(), "Numeric paint order and contents agree");
    }

    @Test void buildsRoadAndBuildingContextWithoutAnyLegacy2DEditor() {
        var session = session();
        send(session, Action.ADD_COMPONENT, "", "road");
        send(session, Action.TERRAIN, "road", "4");
        send(session, Action.EDGE, "road", "2");
        assertEquals(4, session.board().getHex(at).terrainLevel(Terrains.ROAD));
        assertEquals(4, session.board().getHex(at).getTerrain(Terrains.ROAD).getExits());
        send(session, Action.ADD_COMPONENT, "", "building");
        assertEquals(15, session.board().getHex(at).terrainLevel(Terrains.BLDG_CF));
        assertEquals(1, session.board().getHex(at).terrainLevel(Terrains.BLDG_ELEV));
        send(session, Action.UNDO, "", "");
        assertFalse(session.board().getHex(at).containsTerrain(Terrains.BUILDING));
        assertEquals(4, session.board().getHex(at).getTerrain(Terrains.ROAD).getExits());
        send(session, Action.REDO, "", "");
        assertEquals(15, session.board().getHex(at).terrainLevel(Terrains.BLDG_CF));
    }

    @Test void composesThreeCarsAndTreeWithIndependentTransformsAndHeights() throws Exception {
        var session = session();
        for (int i = 0; i < 3; i++) {
            send(session, Action.ASSET, "", "scenery/vehicles/car");
            session.pointer(at, -.25 + i * .2, .15, false); session.finishStroke();
            send(session, Action.OBJECT_VALUE, "rotation", Integer.toString(i * 45));
            send(session, Action.OBJECT_VALUE, "scale", "0.8");
            send(session, Action.OBJECT_VALUE, "level", Integer.toString(i));
        }
        send(session, Action.ASSET, "", "vegetation/birch-young");
        session.pointer(at, .3, -.2, false); session.finishStroke();
        send(session, Action.OBJECT_VALUE, "scale", "0.4");
        assertEquals(4, session.snapshot().objects().size());
        var copy = session.snapshot().objects();
        Path target = directory.resolve("composition.board2"); session.save(target);
        assertFalse(session.dirty());
        assertEquals(copy, BoardFile.read(target).getHex(at).getDecorations());
        send(session, Action.COPY, "", "");
        send(session, Action.TOOL, "", "SELECT");
        session.pointer(new Coords(2, 1), 0, 0, false);
        send(session, Action.PASTE, "", "");
        assertEquals(4, session.snapshot().objects().size());
        assertTrue(session.snapshot().objects().stream().noneMatch(d -> copy.stream().anyMatch(old -> old.id().equals(d.id()))));
        send(session, Action.UNDO, "", "");
        assertFalse(session.dirty(), "Undo back to the saved document clears dirty state");
    }

    @Test void strokeHistoryAndFailedLoadsCannotLoseTheCurrentDocument() throws Exception {
        var session = session();
        long revision = session.snapshot().revision();
        session.adjustElevation(null, 1); session.adjustElevation(at, 0);
        assertEquals(revision, session.snapshot().revision(), "Empty wheel captures must not rebuild the inspector during another drag");
        session.adjustElevation(at, 3); session.adjustElevation(new Coords(2, 2), 4);
        session.finishStroke();
        assertEquals(3, session.board().getHex(at).getLevel());
        send(session, Action.UNDO, "", "");
        assertEquals(0, session.board().getHex(at).getLevel());
        assertEquals(0, session.board().getHex(2, 2).getLevel());
        send(session, Action.REDO, "", "");
        Path broken = directory.resolve("broken.board2"); Files.writeString(broken, "{broken json");
        assertThrows(java.io.IOException.class, () -> session.open(broken));
        assertEquals(3, session.board().getHex(at).getLevel());
        assertTrue(session.dirty());
    }

    @Test void blueprintIsCompleteAndOwnerQueriesDoNotMutateLegacyRules() {
        var blueprint = BoardEditorBlueprint.get();
        var covered = blueprint.components().stream().flatMap(c -> c.fields().stream()).map(BoardEditorBlueprint.Field::terrain).toList();
        assertEquals(covered.size(), covered.stream().distinct().count());
        // The import takes bldg_fluff, so no editor document holds one and the blueprint has no field for it.
        for (int type = 1; type < Terrains.SIZE; type++) {
            if (type != Terrains.BLDG_FLUFF) { assertTrue(covered.contains(Terrains.getName(type)), Terrains.getName(type)); }
        }
        var hex = new megamek.common.Hex(0, "road:1:9;fluff:12;woods:1;foliage_elev:2", "");
        hex.setAppearance(Map.of("road", new megamek.common.board.HexAppearance("road/rounded", null, null, null)));
        var query = blueprint.artwork(hex, "road");
        assertEquals(1, query.terrainLevel(Terrains.ROAD_FLUFF));
        assertEquals(12, query.terrainLevel(Terrains.FLUFF));
        assertFalse(hex.containsTerrain(Terrains.ROAD_FLUFF));
        assertEquals(9, hex.getTerrain(Terrains.ROAD).getExits());
    }

    @Test void spaceAndWallPaletteBrushesPaintSaveAndUndoTheirActualTerrain() throws Exception {
        var blueprint = BoardEditorBlueprint.get();
        assertTrue(blueprint.entry("space/empty").sections().contains("ground-water/basic"));
        assertTrue(blueprint.entry("building/wall").sections().contains("buildings/walls"));
        var session = session();
        send(session, Action.CHOOSE_BRUSH, "space", "empty");
        session.pointer(at, 0, 0, false); session.finishStroke();
        assertEquals(1, session.board().getHex(at).terrainLevel(Terrains.SPACE));
        Path file = directory.resolve("space-and-walls.board2");
        session.save(file);
        assertEquals(1, BoardFile.read(file).getHex(at).terrainLevel(Terrains.SPACE));
        send(session, Action.UNDO, "", "");
        assertFalse(session.board().getHex(at).containsTerrain(Terrains.SPACE));
        send(session, Action.REDO, "", "");
        assertEquals(1, session.board().getHex(at).terrainLevel(Terrains.SPACE));

        send(session, Action.CHOOSE_BRUSH, "building", "wall");
        Coords wall = new Coords(2, 2), next = wall.translated(0);
        session.pointer(wall, 0, 0, false); session.pointer(next, 0, 0, true); session.finishStroke();
        for (Coords spot : List.of(wall, next)) {
            assertEquals(5, session.board().getHex(spot).terrainLevel(Terrains.BUILDING));
            assertEquals(120, session.board().getHex(spot).terrainLevel(Terrains.BLDG_CF));
        }
        assertTrue(session.board().getHex(wall).containsTerrainExit(Terrains.BUILDING, 0));
        assertTrue(session.board().getHex(next).containsTerrainExit(Terrains.BUILDING, 3));
        session.save(file);
        assertEquals(5, BoardFile.read(file).getHex(wall).terrainLevel(Terrains.BUILDING));
    }

    @Test void paintsConfiguredRoadShapeAndMaterialTogetherAsOneUndoableStroke() {
        var session = session();
        send(session, Action.ADD_COMPONENT, "", "road");
        send(session, Action.COMPONENT, "", "road");
        send(session, Action.TERRAIN, "road", "4");
        send(session, Action.VARIANT, "road", "road/rounded");
        send(session, Action.VARIANT, "road", "road/material/gravel");
        send(session, Action.SAMPLE, "", "");
        Coords second = new Coords(2, 1), third = new Coords(3, 1);
        session.pointer(second, 0, 0, false); session.pointer(third, 0, 0, true); session.finishStroke();
        for (Coords coordinate : java.util.List.of(second, third)) {
            var hex = session.board().getHex(coordinate);
            assertEquals(4, hex.terrainLevel(Terrains.ROAD));
            assertEquals("road/rounded", hex.getAppearance().get("road").variant());
            assertEquals("gravel", hex.getAppearance().get("road").material());
        }
        send(session, Action.UNDO, "", "");
        assertFalse(session.board().getHex(second).containsTerrain(Terrains.ROAD));
        assertFalse(session.board().getHex(third).containsTerrain(Terrains.ROAD));
        assertEquals("gravel", session.board().getHex(at).getAppearance().get("road").material());
    }

    @Test void malformedInputsDoNotEscapeTheCommandBoundary() {
        var session = session();
        assertDoesNotThrow(() -> send(session, Action.NEW, "", "16"));
        assertEquals(16, session.board().getWidth());
        send(session, Action.VARIANT, "road", "road/material/dirt");
        assertTrue(session.board().getHex(at).getAppearance().isEmpty(), "A road finish needs a road");
    }

    @Test void choosingAndConfiguringABrushDoesNotReadOrChangeTheInspectedHex() {
        var session = session();
        send(session, Action.CHOOSE_BRUSH, "vegetation", "jungle-1");
        send(session, Action.BRUSH_VALUE, "terrain:jungle", "3");
        send(session, Action.BRUSH_VALUE, "terrain:foliage_elev", "3");
        assertEquals(BoardEditorSession.Tool.PAINT, session.snapshot().tool());
        assertFalse(session.dirty()); assertFalse(session.board().getHex(at).containsTerrain(Terrains.JUNGLE));
        send(session, Action.TOOL, "", "SELECT");
        Coords another = new Coords(4, 4); session.pointer(another, 0, 0, false); session.finishStroke();
        send(session, Action.TERRAIN, "woods", "1");
        send(session, Action.TOOL, "", "PAINT"); session.pointer(at, 0, 0, false); session.finishStroke();
        assertEquals(3, session.board().getHex(at).terrainLevel(Terrains.JUNGLE));
        assertEquals(3, session.board().getHex(at).terrainLevel(Terrains.FOLIAGE_ELEV));
        assertTrue(session.board().getHex(at).isValid(null));
        assertEquals(1, session.board().getHex(another).terrainLevel(Terrains.WOODS));
        send(session, Action.ASSET, "", "scenery/vehicles/car");
        send(session, Action.BRUSH_VALUE, "object:scale", "1.5");
        send(session, Action.BRUSH_VALUE, "object:rotation", "75");
        assertTrue(session.snapshot().objects().isEmpty());
        session.pointer(at, .1, -.1, false); session.finishStroke();
        assertEquals(1.5, session.snapshot().objects().getFirst().scale());
        assertEquals(75, session.snapshot().objects().getFirst().rotation());
        send(session, Action.OBJECT_VALUE, "scale", ".5");
        assertEquals(1.5, session.snapshot().activeBrush().object().scale(), "An inspector edit cannot silently change the brush");
        send(session, Action.CHOOSE_BRUSH, "road", "1");
        assertNull(session.snapshot().activeBrush().object(), "Terrain replaces the object brush instead of leaving a second active brush");
    }

    @Test void movingAnObjectGroupPreservesRelativeTransformsAndOneUndoRestoresEverything() {
        var session = session();
        send(session, Action.ASSET, "", "scenery/vehicles/car");
        send(session, Action.BRUSH_VALUE, "precision", "true");
        session.pointer(at, -.2, .1, false); session.finishStroke();
        String first = session.snapshot().object();
        session.pointer(at, .2, -.1, false); session.finishStroke();
        String second = session.snapshot().object();
        var before = placedObjects(session);
        send(session, Action.TOOL, "", "SELECT");
        session.pointer(at, -.2, .1, false, first); session.finishStroke();
        session.pointer(at, .2, -.1, false, second, true); session.finishStroke();
        assertEquals(2, session.snapshot().selection().size());
        session.pointer(at, -.2, .1, false, first);
        session.pointer(at, 1.8, -.5, true, first);
        assertEquals(at, session.snapshot().selected(), "The original owner is stable until release");
        assertEquals(first, session.snapshot().object());
        assertEquals(2, session.board().getHex(at).getDecorations().size());
        var preview = new java.util.HashSet<>(session.snapshot().movePreview());
        assertFalse(preview.contains(at), "Destination ground footprints follow the moving group");
        session.finishStroke();
        var moved = placedObjects(session);
        assertEquals(2, moved.size());
        assertTrue(session.board().getHex(at).getDecorations().isEmpty(), "Moved anchors belong to their destination hex");
        for (var entry : moved.entrySet()) {
            Coords dest = entry.getKey().coords();
            var original = before.get(new BoardEditorSession.Selection(at, entry.getKey().object()));
            assertNotNull(original);
            assertEquals(at.getX() * .75 + original.x() + 2, dest.getX() * .75 + entry.getValue().x(), .00001);
            assertEquals(-at.getY() - (at.getX() & 1) * .5 + original.y() - .6,
                  -dest.getY() - (dest.getX() & 1) * .5 + entry.getValue().y(), .00001);
            assertTrue(Math.abs(entry.getValue().x()) <= .5 && Math.abs(entry.getValue().y()) <= .5);
        }
        assertEquals(moved.keySet(), new java.util.HashSet<>(session.snapshot().selection()));
        assertEquals(preview, moved.keySet().stream().map(BoardEditorSession.Selection::coords).collect(java.util.stream.Collectors.toSet()),
              "Release must use the exact highlighted destinations");
        send(session, Action.UNDO, "", ""); assertEquals(before, placedObjects(session));
        send(session, Action.REDO, "", ""); assertEquals(moved, placedObjects(session));
    }

    @Test void dragRetainsItsObjectAcrossHexBoundariesAndRejectsAnOutsideCentre() {
        var session = session();
        send(session, Action.ASSET, "", "vegetation/birch-young"); session.pointer(at, 0, 0, false); session.finishStroke();
        String id = session.snapshot().object();
        send(session, Action.TOOL, "", "SELECT"); session.pointer(at, 0, 0, false, id);
        for (double x : new double[] { .5, 1.2, .4, 1.7 }) {
            session.pointer(at, x, 0, true, id);
            assertEquals(id, session.snapshot().object()); assertEquals(at, session.snapshot().selected());
            assertEquals(java.util.List.of(new BoardEditorSession.Selection(at, id)), session.snapshot().selection());
            assertEquals(1, placedObjects(session).size());
        }
        var last = session.snapshot().objects(); var target = session.snapshot().movePreview().getFirst();
        session.pointer(at, -20, 0, true, id);
        assertEquals(last, session.snapshot().objects(), "An invalid move retains the last valid pose");
        assertEquals(java.util.List.of(target), session.snapshot().movePreview());
        session.finishStroke();
        assertEquals(target, session.snapshot().selected()); assertEquals(id, session.snapshot().object());
        assertEquals(java.util.List.of(new BoardEditorSession.Selection(target, id)), session.snapshot().selection());
    }

    @Test void clickingAndTransformingObjectsPreserveContentsOrderWithoutNoOpHistory() {
        var session = session();
        var first = new megamek.common.board.BoardDecoration("z", "prop", "scenery/vehicles/car", null,
              -.1, .1, 0, false, 1, megamek.common.board.BoardDecoration.Placement.ground(), 0);
        var middle = new megamek.common.board.BoardDecoration("a", "prop", "scenery/vehicles/car", null,
              0, -.1, 0, false, 1, megamek.common.board.BoardDecoration.Placement.ground(), 0);
        var last = new megamek.common.board.BoardDecoration("x", "prop", "vegetation/birch-young", null,
              .1, .2, 0, false, 1, megamek.common.board.BoardDecoration.Placement.ground(), 0);
        var original = java.util.List.of(first, middle, last);
        session.board().getHex(at).setDecorations(original);
        for (var object : java.util.List.of(middle, last, first)) {
            session.pointer(at, object.x(), object.y(), false, object.id()); session.finishStroke();
            assertEquals(original, session.board().getHex(at).getDecorations(), "A click cannot reorder or round local offsets");
            assertFalse(session.dirty()); assertFalse(session.snapshot().canUndo(), "A click is not a document edit");
        }
        send(session, Action.OBJECT_VALUE, "scale", "1.5");
        assertEquals(java.util.List.of("z", "a", "x"), session.snapshot().objects().stream().map(object -> object.id()).toList());
        send(session, Action.UNDO, "", "");
        assertEquals(original, session.board().getHex(at).getDecorations());
        assertFalse(session.snapshot().canUndo());
        session.pointer(at, last.x(), last.y(), false, last.id()); session.finishStroke();
        session.pointer(at, first.x(), first.y(), false, first.id(), true); session.finishStroke();
        session.pointer(at, last.x(), last.y(), false, last.id());
        session.pointer(at, last.x() + .05, last.y(), true, last.id());
        assertEquals(java.util.List.of("z", "a", "x"), session.board().getHex(at).getDecorations().stream().map(object -> object.id()).toList(),
              "Group selection order cannot reorder same-owner objects during movement");
        session.finishStroke(); send(session, Action.UNDO, "", "");
        assertEquals(original, session.board().getHex(at).getDecorations());
        assertFalse(session.snapshot().canUndo(), "The move remains a single undo operation");
    }

    @Test void objectEditsKeepTheirGroupAndPastedGroupsAreFresh() {
        var session = session();
        var ground = megamek.common.board.BoardDecoration.Placement.ground();
        var table = new megamek.common.board.BoardDecoration("table", "prop", "scenery/vehicles/car", null,
              -.1, .1, 0, false, 1, ground, 0).withGroup("picnic");
        var bench = new megamek.common.board.BoardDecoration("bench", "prop", "scenery/vehicles/car", null,
              .1, .1, 0, false, 1, ground, 0).withGroup("picnic");
        var loose = new megamek.common.board.BoardDecoration("loose", "prop", "vegetation/birch-young", null,
              0, -.2, 0, false, 1, ground, 0);
        session.board().getHex(at).setDecorations(java.util.List.of(table, bench, loose));
        send(session, Action.SELECT_OBJECT, "", "table");
        send(session, Action.OBJECT_VALUE, "rotation", "30");
        assertEquals("picnic", session.board().getHex(at).getDecorations().getFirst().group());
        send(session, Action.COPY, "", "");
        send(session, Action.TOOL, "", "SELECT");
        session.pointer(new Coords(2, 1), 0, 0, false);
        send(session, Action.PASTE, "", "");
        var pasted = session.board().getHex(new Coords(2, 1)).getDecorations();
        assertEquals(3, pasted.size());
        assertNotEquals("picnic", pasted.get(0).group(), "A pasted group never joins its original");
        assertEquals(pasted.get(0).group(), pasted.get(1).group());
        assertNull(pasted.get(2).group());
    }

    @Test void draggingOffASupportFallsBackToGroundAndKeepsItsHeightOffset() {
        for (String receiver : java.util.List.of("bridge", "building", "industrial", "fuelTank", "ice")) {
            var session = session();
            String terrain = switch (receiver) {
                case "bridge" -> "bridge:1:9;bridge_cf:40;bridge_elev:4";
                case "building" -> "building:1;bldg_cf:15;bldg_elev:4";
                case "fuelTank" -> "fuel_tank:1;fuel_tank_cf:15;fuel_tank_elev:4;fuel_tank_magn:100";
                case "ice" -> "water:1;ice:1";
                default -> "heavy_industrial:4";
            };
            session.board().setHex(at, new megamek.common.Hex(0, terrain, ""));
            Coords destination = new Coords(at.getX() + 2, at.getY());
            session.board().setHex(destination, new megamek.common.Hex(2));
            String surface = receiver.equals("bridge") ? "deck" : receiver.equals("building") ? "roof" : "top";
            var prop = new megamek.common.board.BoardDecoration("prop", "prop", "scenery/vehicles/car", null,
                  0, 0, 0, false, 1, megamek.common.board.BoardDecoration.Placement.surface(receiver, surface, .4), 0);
            var decal = new megamek.common.board.BoardDecoration("decal", "decal", "scenery/decals/scorch", null,
                  0, 0, 0, false, 1, megamek.common.board.BoardDecoration.Placement.surface(receiver, surface, 0), 0);
            session.board().getHex(at).setDecorations(java.util.List.of(prop, decal));
            session.pointer(at, 0, 0, false, prop.id()); session.finishStroke();
            session.pointer(at, 0, 0, false, decal.id(), true); session.finishStroke();
            session.pointer(at, 0, 0, false, prop.id());
            session.pointer(at, 1.5, 0, true, prop.id());
            assertEquals(at, session.snapshot().selected(), "The owner still changes only on release");
            assertTrue(session.snapshot().objects().stream().allMatch(object -> object.placement().receiver().terrain().equals("ground")));
            session.pointer(at, 0, 0, true, prop.id());
            assertEquals(java.util.List.of(prop, decal), session.snapshot().objects(), "Moving back restores the original attachments");
            session.pointer(at, 1.5, 0, true, prop.id()); session.finishStroke();
            assertEquals(destination, session.snapshot().selected());
            assertEquals(.4, session.snapshot().objects().getFirst().placement().offset());
            assertTrue(session.snapshot().objects().stream().allMatch(object -> object.placement().receiver().terrain().equals("ground")));
            send(session, Action.UNDO, "", "");
            assertEquals(java.util.List.of(prop, decal), session.board().getHex(at).getDecorations());
            assertTrue(session.board().getHex(destination).getDecorations().isEmpty());
        }
    }

    @Test void aPropPutDownOnIceStandsOnTheIce() {
        var session = session();
        session.board().setHex(at, new megamek.common.Hex(0, "water:1;ice:1", ""));
        send(session, Action.ASSET, "", "vegetation/birch-young"); session.pointer(at, 0, 0, false); session.finishStroke();
        assertEquals(megamek.common.board.BoardDecoration.Placement.surface("ice", "top", 0),
              session.snapshot().objects().getFirst().placement());
    }

    @Test void magneticConnectorsNeedMatchingSetOrientationScaleAndHeight() {
        var blueprint = BoardEditorBlueprint.get();
        var board = megamek.common.board.Board.createEmptyBoard(12, 12);
        Coords target = new Coords(5, 5);
        String barrier = "scenery/vehicles/parking-barrier";
        var fixed = new megamek.common.board.BoardDecoration("fixed", "prop", barrier, null,
              0, 0, 0, false, 1, megamek.common.board.BoardDecoration.Placement.ground(), 0);
        board.getHex(target).setDecorations(java.util.List.of(fixed));
        // A barrier's ends lie half its length north and south of its anchor: the next barrier joins one length north.
        double join = 2 * blueprint.asset(barrier).snap().connectors().getFirst().y() / HexTile.HEIGHT;
        var moving = new megamek.common.board.BoardDecoration("moving", "prop", barrier, null,
              .02, join + .03, 0, false, 1, megamek.common.board.BoardDecoration.Placement.ground(), 0);
        var snapped = BoardEditorSnapping.snap(board, blueprint, target, moving, java.util.Set.of());
        assertEquals(0, snapped.x(), .000001); assertEquals(join, snapped.y(), .000001);
        assertEquals(moving.id(), snapped.id()); assertEquals(moving.rotation(), snapped.rotation());
        for (var invalid : java.util.List.of(moving.transform(.02, join + .03, 30, false, 1, moving.placement()),
              new megamek.common.board.BoardDecoration(moving.id(), moving.kind(), moving.asset(), null, .02, join + .03, 0,
                    false, 1, moving.placement(), 0, false, 12, 0),
              moving.transform(.02, join + .03, 0, false, 2, moving.placement()),
              moving.transform(.02, join + .03, 0, false, 1, megamek.common.board.BoardDecoration.Placement.surface("ground", "top", 1)),
              moving.transform(.4, join + .03, 0, false, 1, moving.placement()))) {
            assertEquals(invalid, BoardEditorSnapping.snap(board, blueprint, target, invalid, java.util.Set.of()));
        }
        assertEquals(moving, BoardEditorSnapping.snap(board, blueprint, target, moving, java.util.Set.of("fixed")), "A moving group never snaps to itself");
        // Turned half round and mirrored, the barrier's ends are where they were.
        var mirrored = moving.transform(.02, join + .03, 180, true, 1, moving.placement());
        var mirrorSnap = BoardEditorSnapping.snap(board, blueprint, target, mirrored, java.util.Set.of());
        assertEquals(0, mirrorSnap.x(), .000001); assertEquals(join, mirrorSnap.y(), .000001);
        assertNull(blueprint.asset("scenery/construction/concrete-pipe").snap(), "A vertical pipe is not a horizontal rail connector");
        // Stretched twice as long, both barriers' ends lie a whole length from their anchors; a plain one does not join them.
        var longer = new megamek.common.board.BoardDecoration.Stretch(1, 2, 1);
        var longMoving = moving.transform(.02, 2 * join + .03, 0, false, 1, moving.placement()).withStretch(longer);
        assertEquals(longMoving, BoardEditorSnapping.snap(board, blueprint, target, longMoving, java.util.Set.of()));
        board.getHex(target).setDecorations(java.util.List.of(fixed.withStretch(longer)));
        var longSnap = BoardEditorSnapping.snap(board, blueprint, target, longMoving, java.util.Set.of());
        assertEquals(0, longSnap.x(), .000001); assertEquals(2 * join, longSnap.y(), .000001);
        assertEquals(longer, longSnap.stretch());
    }

    @Test void aPavedRoadOnLunarGroundIsLaidInColonySlabsUnlessAnotherFinishIsChosen() {
        var session = session();
        session.board().getHex(at).setTheme("lunar");
        send(session, Action.TERRAIN, "road", "1");
        assertEquals(megamek.common.board.BoardSceneryLayouts.COLONY_SLABS,
              session.board().getHex(at).getAppearance().get("road").material());
        // Any finish stays the map's choice, asphalt too, through later changes of the road.
        send(session, Action.VARIANT, "road", "road/material/asphalt");
        assertEquals("asphalt", session.board().getHex(at).getAppearance().get("road").material());
        send(session, Action.TERRAIN, "road", "2");
        assertEquals("asphalt", session.board().getHex(at).getAppearance().get("road").material());
        // The road brush lays the ground's finish where it paints.
        Coords other = new Coords(3, 3);
        session.board().getHex(other).setTheme("lunar");
        send(session, Action.CHOOSE_BRUSH, "road", "1");
        send(session, Action.BRUSH_VALUE, "terrain:road", "1");
        session.pointer(other, 0, 0, false); session.finishStroke();
        assertEquals(megamek.common.board.BoardSceneryLayouts.COLONY_SLABS,
              session.board().getHex(other).getAppearance().get("road").material());
        // The slabs card lays them on any ground, and a paved road keeps them through a change of its level.
        Coords earth = new Coords(5, 5);
        send(session, Action.CHOOSE_BRUSH, "road", "road/material/colony-slabs");
        session.pointer(earth, 0, 0, false); session.finishStroke();
        assertEquals(megamek.common.board.BoardSceneryLayouts.COLONY_SLABS,
              session.board().getHex(earth).getAppearance().get("road").material());
        session.pointer(earth, 0, 0, false);
        send(session, Action.TERRAIN, "road", "2");
        assertEquals(megamek.common.board.BoardSceneryLayouts.COLONY_SLABS,
              session.board().getHex(earth).getAppearance().get("road").material());
    }

    @Test void roadChoicesAlwaysCarryTheirDefinedFinishAndRemovingAnotherObjectKeepsSelection() {
        var session = session();
        for (var choice : BoardEditorBlueprint.get().component("road").fields().getFirst().choices()) {
            send(session, Action.CHOOSE_BRUSH, "road", "1");
            send(session, Action.BRUSH_VALUE, "terrain:road", Integer.toString(choice.value()));
            session.pointer(at, 0, 0, false); session.finishStroke();
            assertEquals(choice.material(), session.board().getHex(at).getAppearance().get("road").material());
            assertEquals(choice.value(), session.board().getHex(at).terrainLevel(Terrains.ROAD));
        }
        send(session, Action.TERRAIN, "road", "2");
        assertEquals("asphalt", session.board().getHex(at).getAppearance().get("road").material());
        send(session, Action.VARIANT, "road", "road/material/dirt");
        assertEquals(3, session.board().getHex(at).terrainLevel(Terrains.ROAD));
        send(session, Action.ASSET, "", "vegetation/birch-young"); session.pointer(at, 0, 0, false); session.finishStroke();
        String first = session.snapshot().object();
        send(session, Action.BRUSH_VALUE, "object:rotation", "90"); session.pointer(at, 0, 0, false); session.finishStroke();
        String second = session.snapshot().object(); send(session, Action.REMOVE_OBJECT, first, "");
        assertEquals(second, session.snapshot().object()); assertEquals(1, session.snapshot().objects().size());
    }

    private static megamek.common.board.BoardDecoration prop(String id, double x, double y) {
        return new megamek.common.board.BoardDecoration(id, "prop", "scenery/vehicles/car", null, x, y, 0, false, 1,
              megamek.common.board.BoardDecoration.Placement.ground(), 0);
    }
    private static java.util.Set<String> ids(BoardEditorSession session) {
        return session.snapshot().selection().stream().map(BoardEditorSession.Selection::object).collect(java.util.stream.Collectors.toSet());
    }
    private void click(BoardEditorSession session, Coords coords, String id, boolean shift) {
        session.pointer(coords, 0, 0, false, id, shift); session.finishStroke(); session.release();
    }
    /** Board-global metric (pixel) position of an object, as the renderer places it. */
    private double[] metric(BoardEditorSession session, String id) {
        var entry = placedObjects(session).entrySet().stream().filter(e -> e.getKey().object().equals(id)).findFirst().orElseThrow();
        Coords owner = entry.getKey().coords();
        return new double[] { (owner.getX() * .75 + entry.getValue().x()) * HexTile.MODEL_WIDTH,
              (-owner.getY() - (owner.getX() & 1) * .5 + entry.getValue().y()) * HexTile.HEIGHT };
    }
    private String group(BoardEditorSession session, String id) {
        return placedObjects(session).entrySet().stream().filter(e -> e.getKey().object().equals(id)).findFirst().orElseThrow().getValue().group();
    }

    @Test void groupsAreOneUndoStepAndClicksSelectWholeGroupsWithDrillDown() {
        var session = session();
        Coords east = new Coords(2, 1);
        session.board().getHex(at).setDecorations(java.util.List.of(prop("a", -.2, .1), prop("loose", .2, -.2)));
        session.board().getHex(east).setDecorations(java.util.List.of(prop("c", 0, 0)));
        session.select(java.util.List.of(new BoardEditorSession.Selection(at, "a"), new BoardEditorSession.Selection(east, "c"),
              new BoardEditorSession.Selection(at, "missing")), BoardEditorSession.SelectMode.REPLACE);
        assertEquals(java.util.Set.of("a", "c"), ids(session), "A box selection ignores unknown objects");
        session.key(java.awt.event.KeyEvent.VK_G, java.awt.event.InputEvent.CTRL_DOWN_MASK, null);
        String group = group(session, "a");
        assertNotNull(group); assertEquals(group, group(session, "c")); assertNull(group(session, "loose"));
        assertEquals(java.util.List.of(group), session.snapshot().groups()); assertEquals(group, session.snapshot().group());
        send(session, Action.UNDO, "", "");
        assertNull(group(session, "a")); assertNull(group(session, "c"));
        assertFalse(session.snapshot().canUndo(), "Grouping members in two hexes is one undo step");
        send(session, Action.REDO, "", "");
        assertEquals(group, group(session, "c"));

        send(session, Action.CLEAR_SELECTION, "", "");
        click(session, at, "a", false);
        assertEquals(java.util.Set.of("a", "c"), ids(session), "Clicking a member selects its whole group");
        assertEquals("a", session.snapshot().object());
        click(session, at, "a", false);
        assertEquals(java.util.Set.of("a"), ids(session), "A second click without a drag drills down to the member");
        assertEquals("", session.snapshot().group());
        click(session, east, "c", false);
        assertEquals(java.util.Set.of("c"), ids(session), "While drilled in, a sibling's click selects only the sibling");
        click(session, at, "loose", false);
        assertEquals(java.util.Set.of("loose"), ids(session));
        click(session, at, "a", true);
        assertEquals(java.util.Set.of("loose", "a", "c"), ids(session), "Shift adds the whole group");
        assertEquals(java.util.List.of("", group), session.snapshot().groups().stream().sorted().toList());
        assertEquals("", session.snapshot().group(), "A group plus a loose object is not one group");
        click(session, east, "c", true);
        assertEquals(java.util.Set.of("loose"), ids(session), "Shift removes the whole group");
        session.select(java.util.List.of(new BoardEditorSession.Selection(east, "c")), BoardEditorSession.SelectMode.ADD);
        assertEquals(java.util.Set.of("loose", "a", "c"), ids(session), "An additive box selection expands to the group");
        session.select(java.util.List.of(new BoardEditorSession.Selection(east, "c")), BoardEditorSession.SelectMode.REPLACE);
        assertEquals(java.util.Set.of("a", "c"), ids(session));

        double[] a = metric(session, "a"), c = metric(session, "c");
        session.pointer(at, -.2, .1, false, "a");
        session.pointer(at, .8, .1, true, "a");
        session.finishStroke(); session.release();
        assertEquals(java.util.Set.of("a", "c"), ids(session), "A press that drags moves the group instead of drilling down");
        double[] movedA = metric(session, "a"), movedC = metric(session, "c");
        assertEquals(HexTile.MODEL_WIDTH, movedA[0] - a[0], 1e-6); assertEquals(HexTile.MODEL_WIDTH, movedC[0] - c[0], 1e-6);
        assertEquals(a[1], movedA[1], 1e-6); assertEquals(c[1], movedC[1], 1e-6);
        assertEquals(group, group(session, "a"), "A move keeps membership across owner hexes");

        session.key(java.awt.event.KeyEvent.VK_G, java.awt.event.InputEvent.CTRL_DOWN_MASK | java.awt.event.InputEvent.SHIFT_DOWN_MASK, null);
        assertNull(group(session, "a")); assertNull(group(session, "c"));
        send(session, Action.CLEAR_SELECTION, "", "");
        Coords ownerA = placedObjects(session).keySet().stream().filter(s -> s.object().equals("a")).findFirst().orElseThrow().coords();
        click(session, ownerA, "a", false);
        assertEquals(java.util.Set.of("a"), ids(session), "The group index follows ungrouping");
        send(session, Action.UNDO, "", "");
        assertEquals(group, group(session, "a"), "Undoing ungroup is one step");
        click(session, ownerA, "a", false);
        assertEquals(java.util.Set.of("a", "c"), ids(session), "The group index is rebuilt after undo");
        session.key(java.awt.event.KeyEvent.VK_G, java.awt.event.InputEvent.CTRL_DOWN_MASK, null);
        assertEquals("The selection is already one group.", session.snapshot().message());
        assertEquals(group, group(session, "a"));
    }

    @Test void groupTransformsPivotAboutTheCentroidAndDuplicateIntoAFreshGroup() {
        var session = session();
        Coords east = new Coords(2, 1);
        session.board().getHex(at).setDecorations(java.util.List.of(prop("a", -.2, .1).withGroup("g")));
        session.board().getHex(east).setDecorations(java.util.List.of(prop("b", .1, -.1).withGroup("g")));
        send(session, Action.SELECT_OBJECT, "g", "");
        assertEquals("g", session.snapshot().group());
        assertEquals("a", session.snapshot().object(), "The inspected hex's member is the primary");
        double[] a = metric(session, "a"), b = metric(session, "b");
        double[] centre = { (a[0] + b[0]) / 2, (a[1] + b[1]) / 2 };
        double distance = Math.hypot(a[0] - b[0], a[1] - b[1]);

        send(session, Action.GROUP_VALUE, "rotation", "90");
        double[] turnedA = metric(session, "a"), turnedB = metric(session, "b");
        assertEquals(distance, Math.hypot(turnedA[0] - turnedB[0], turnedA[1] - turnedB[1]), 1e-6, "Yaw keeps metric distances");
        assertEquals(centre[0], (turnedA[0] + turnedB[0]) / 2, 1e-6); assertEquals(centre[1], (turnedA[1] + turnedB[1]) / 2, 1e-6);
        assertEquals(centre[0] - (a[1] - centre[1]), turnedA[0], 1e-6, "Counter-clockwise like an object's yaw");
        assertEquals(90, placedObjects(session).values().stream().filter(o -> o.id().equals("b")).findFirst().orElseThrow().rotation(), 1e-9);
        send(session, Action.UNDO, "", "");
        assertArrayEquals(a, metric(session, "a"), 1e-9); assertFalse(session.snapshot().canUndo(), "A group turn is one undo step");

        send(session, Action.SELECT_OBJECT, "g", "");
        send(session, Action.GROUP_VALUE, "scale", "2");
        assertEquals(2 * distance, Math.hypot(metric(session, "a")[0] - metric(session, "b")[0], metric(session, "a")[1] - metric(session, "b")[1]), 1e-6);
        send(session, Action.UNDO, "", "");

        send(session, Action.SELECT_OBJECT, "g", "");
        send(session, Action.GROUP_VALUE, "mirror", "true");
        assertEquals(2 * centre[0] - a[0], metric(session, "a")[0], 1e-6, "Mirror reflects about the centroid");
        assertEquals(a[1], metric(session, "a")[1], 1e-6);
        assertTrue(placedObjects(session).values().stream().allMatch(megamek.common.board.BoardDecoration::mirror));
        send(session, Action.UNDO, "", "");

        session.pointer(at, 0, 0, false); session.finishStroke(); // The mirror moved the inspected hex to b's owner.
        send(session, Action.SELECT_OBJECT, "g", "");
        assertEquals("a", session.snapshot().object());
        send(session, Action.GROUP_VALUE, "x", "0.3");
        assertEquals(a[0] + .3 * HexTile.MODEL_WIDTH, metric(session, "a")[0], 1e-6);
        assertEquals(b[0] + .3 * HexTile.MODEL_WIDTH, metric(session, "b")[0], 1e-6);
        send(session, Action.UNDO, "", "");

        // Values are relative to the group as it is (yaw 0, scale 1): a live drag's values are its total change, and
        // the next edit starts from the result.
        send(session, Action.SELECT_OBJECT, "g", "");
        for (String value : java.util.List.of("10", "20", "30")) { session.command(new Command(Action.GROUP_VALUE, "rotation", value), null, true); }
        session.finishStroke();
        assertEquals(30, placedObjects(session).values().stream().filter(o -> o.id().equals("b")).findFirst().orElseThrow().rotation(), 1e-9);
        send(session, Action.GROUP_VALUE, "rotation", "30");
        assertEquals(60, placedObjects(session).values().stream().filter(o -> o.id().equals("b")).findFirst().orElseThrow().rotation(), 1e-9);
        assertEquals(distance, Math.hypot(metric(session, "a")[0] - metric(session, "b")[0], metric(session, "a")[1] - metric(session, "b")[1]), 1e-6);
        send(session, Action.UNDO, "", ""); send(session, Action.UNDO, "", "");
        assertArrayEquals(a, metric(session, "a"), 1e-9); assertFalse(session.snapshot().canUndo(), "The live drag was one undo step");

        send(session, Action.SELECT_OBJECT, "g", "");
        send(session, Action.DUPLICATE_OBJECT, "", "");
        assertEquals(4, placedObjects(session).size());
        assertEquals(2, session.snapshot().selection().size());
        assertFalse(ids(session).contains("a") || ids(session).contains("b"), "The copies become the selection");
        String copy = session.snapshot().group();
        assertFalse(copy.isEmpty()); assertNotEquals("g", copy);
        assertEquals("g", group(session, "a"), "The original group is unchanged");
        send(session, Action.UNDO, "", "");
        assertEquals(2, placedObjects(session).size(), "A whole-group duplicate is one undo step");
    }

    @Test void aGroupRepaintsEveryMemberInOneUndoStep() {
        var session = session();
        Coords east = new Coords(2, 1);
        session.board().getHex(at).setDecorations(java.util.List.of(prop("a", -.2, .1).withGroup("g")));
        session.board().getHex(east).setDecorations(java.util.List.of(prop("b", .1, -.1).withGroup("g")
              .withColours(megamek.common.board.BoardDecoration.Colours.of(null, "#ffffff"))));
        send(session, Action.SELECT_OBJECT, "g", "");
        send(session, Action.GROUP_VALUE, "colour0", "#24407a");
        assertEquals(java.util.List.of(java.util.List.of("#24407a"), java.util.Arrays.asList("#24407a", "#ffffff")),
              java.util.stream.Stream.of("a", "b").map(id -> placedObjects(session).values().stream().filter(o -> o.id().equals(id))
                    .findFirst().orElseThrow().colours().slots()).toList(), "Every member's slot, the other slots kept");
        send(session, Action.UNDO, "", "");
        assertTrue(placedObjects(session).values().stream().filter(o -> o.id().equals("a")).findFirst().orElseThrow()
              .colours().slots().isEmpty());
        assertFalse(session.snapshot().canUndo(), "A group repaint is one undo step");
    }

    /**
     * A converted maglev train's group also holds its platform and the cars parked beside it: a colour chosen on the
     * inspected train car repaints every vehicle of the train (cab and coupler too, other models), and neither the
     * platform nor a parked car; chosen on a parked car, it repaints the cars alone.
     */
    @Test void aTrainGroupRepaintsItsVehiclesAndNotTheCarsParkedBesideIt() {
        var session = session();
        Coords east = new Coords(2, 1);
        java.util.function.BiFunction<String, String, BoardDecoration> member = (id, asset) -> new BoardDecoration(id, "prop",
              asset, null, 0, 0, 0, false, 1, BoardDecoration.Placement.ground(), 0).withGroup("g");
        session.board().getHex(at).setDecorations(List.of(member.apply("wagon", "scenery/maglev/wagon"),
              member.apply("cab", "scenery/maglev/cab"), member.apply("coupler", megamek.common.board.RailRoute.COUPLER),
              member.apply("platform", megamek.common.board.RailRoute.PLATFORM)));
        var grey = BoardDecoration.Colours.of("#b8b8b8");
        session.board().getHex(east).setDecorations(List.of(member.apply("car", "scenery/vehicles/car").withColours(grey),
              member.apply("car-2", "scenery/vehicles/car")));
        send(session, Action.SELECT_OBJECT, "g", "");
        assertEquals("wagon", session.snapshot().object(), "The inspected member");
        send(session, Action.GROUP_VALUE, "colour0", "#24407a");
        Map<String, List<String>> slots = new java.util.HashMap<>();
        placedObjects(session).values().forEach(object -> slots.put(object.id(), object.colours().slots()));
        assertEquals(Map.of("wagon", List.of("#24407a"), "cab", List.of("#24407a"), "coupler", List.of("#24407a"),
              "platform", List.of(), "car", List.of("#b8b8b8"), "car-2", List.of()), slots);
        send(session, Action.CLEAR_SELECTION, "", "");
        click(session, east, "car", false);
        assertEquals(6, session.snapshot().selection().size(), "A click on a member selects its whole group");
        send(session, Action.GROUP_VALUE, "colour0", "#ffffff");
        placedObjects(session).values().forEach(object -> slots.put(object.id(), object.colours().slots()));
        assertEquals(Map.of("wagon", List.of("#24407a"), "cab", List.of("#24407a"), "coupler", List.of("#24407a"),
              "platform", List.of(), "car", List.of("#ffffff"), "car-2", List.of("#ffffff")), slots);
    }

    @Test void groupingADrilledMemberWithALooseObjectSelectsAndTransformsTheWholeMergedGroup() {
        var session = session();
        Coords east = new Coords(2, 1);
        session.board().getHex(at).setDecorations(java.util.List.of(prop("a", -.2, .1).withGroup("g"), prop("c", .2, -.2)));
        session.board().getHex(east).setDecorations(java.util.List.of(prop("b", .1, -.1).withGroup("g")));
        click(session, at, "a", false);
        click(session, at, "a", false);
        assertEquals(java.util.Set.of("a"), ids(session), "Drilled into a");
        click(session, at, "c", true);
        assertEquals(java.util.Set.of("a", "c"), ids(session));
        session.key(java.awt.event.KeyEvent.VK_G, java.awt.event.InputEvent.CTRL_DOWN_MASK, null);
        String merged = group(session, "b");
        assertNotEquals("g", merged); assertEquals(merged, group(session, "a")); assertEquals(merged, group(session, "c"));
        assertEquals(java.util.Set.of("a", "b", "c"), ids(session), "The selection becomes the whole merged group");
        assertEquals(merged, session.snapshot().group());

        send(session, Action.GROUP_VALUE, "rotation", "90");
        assertTrue(placedObjects(session).values().stream().allMatch(o -> Math.abs(o.rotation() - 90) < 1e-9),
              "A group turn reaches the member that was outside the selection");

    }

    @Test void aGroupWithOneMemberLeftIsReportedAsUngrouped() {
        var session = session();
        var solo = prop("x", 0, 0).withGroup("solo");
        var pair = prop("y", .2, 0).withGroup("pair");
        session.board().getHex(at).setDecorations(java.util.List.of(solo, pair));
        session.board().getHex(new Coords(2, 1)).setDecorations(java.util.List.of(prop("z", 0, 0).withGroup("pair")));
        var snapshot = session.snapshot();
        assertEquals(Map.of("solo", 1, "pair", 2), snapshot.groupSizes(), "Sizes count members board-wide");
        assertFalse(snapshot.grouped(solo)); assertTrue(snapshot.grouped(pair));
    }

    @Test void groundClicksSelectGroundAfterInspectingAnotherComponent() {
        var session = session();
        Coords east = new Coords(2, 1);
        send(session, Action.ADD_COMPONENT, "", "bridge");
        send(session, Action.COMPONENT, "", "bridge");
        assertEquals("bridge", session.snapshot().component(), "Layers and the side view can select a present bridge");

        click(session, at, null, false);
        assertEquals("ground", session.snapshot().component(), "Clicking ground in the same hex selects Ground");
        assertEquals(List.of(new BoardEditorSession.Selection(at, "")), session.snapshot().selection());

        send(session, Action.COMPONENT, "", "bridge");
        click(session, east, null, false);
        assertEquals(east, session.snapshot().selected());
        assertEquals("ground", session.snapshot().component(), "A clear hex cannot inherit the previous hex's bridge");

        click(session, at, null, false);
        send(session, Action.COMPONENT, "", "bridge");
        click(session, east, null, true);
        assertEquals("ground", session.snapshot().component(), "Shift-click also inspects the newly selected ground");
        assertEquals(List.of(new BoardEditorSession.Selection(at, ""), new BoardEditorSession.Selection(east, "")),
              session.snapshot().selection());

        session.pointer(at, 0, 0, false, null, false, "bridge"); session.finishStroke(); session.release();
        assertEquals("bridge", session.snapshot().component(), "A click that struck the bridge selects Bridge");
        session.pointer(east, 0, 0, false, null, false, "bridge"); session.finishStroke(); session.release();
        assertEquals("ground", session.snapshot().component(), "A layer the hex lacks is never selected");

        session.board().getHex(east).setDecorations(List.of(prop("car", 0, 0)));
        click(session, east, "car", false);
        assertEquals("car", session.snapshot().object(), "A picked object keeps priority over the ground");
        assertEquals(List.of(new BoardEditorSession.Selection(east, "car")), session.snapshot().selection());
        click(session, east, "car", true);
        assertTrue(session.snapshot().selection().isEmpty());
        assertEquals("", session.snapshot().object(), "Shift-clicking the last object clears its inspector too");
    }

    @Test void issueAndBoxHexSelectionsDoNotInheritTheInspectedComponent() {
        var session = session();
        Coords east = new Coords(2, 1);
        send(session, Action.ADD_COMPONENT, "", "bridge");
        send(session, Action.COMPONENT, "", "bridge");
        send(session, Action.SELECT_AT, east.getX() + "," + east.getY(), "");
        assertEquals(east, session.snapshot().selected());
        assertEquals("ground", session.snapshot().component());
        assertEquals("", session.snapshot().object());

        click(session, at, null, false);
        send(session, Action.COMPONENT, "", "bridge");
        var hexes = List.of(new BoardEditorSession.Selection(east, ""), new BoardEditorSession.Selection(at, ""));
        session.select(hexes, BoardEditorSession.SelectMode.REPLACE);
        assertEquals(hexes, session.snapshot().selection());
        assertEquals(east, session.snapshot().selected());
        assertEquals("ground", session.snapshot().component(), "A box over hexes inspects Ground");
    }

    @Test void onlyASelectedItemMovesAndABoxTakesObjectsBeforeHexes() {
        var session = session();
        Coords east = new Coords(2, 1), far = new Coords(6, 6);
        send(session, Action.ELEVATION, "", "3");
        session.board().getHex(east).setDecorations(java.util.List.of(prop("car", 0, 0)));
        session.board().getHex(far).setDecorations(java.util.List.of(prop("van", 0, 0)));
        send(session, Action.CLEAR_SELECTION, "", "");

        // A press on an unselected hex selects it; its drag moves nothing (the view boxes such drags).
        session.pointer(at, 0, 0, false); session.pointer(far, 0, 0, true); session.finishStroke(); session.release();
        assertEquals(3, session.board().getHex(at).getLevel()); assertEquals(0, session.board().getHex(far).getLevel());
        assertEquals(java.util.List.of(new BoardEditorSession.Selection(at, "")), session.snapshot().selection());
        // Dragging the now selected hex moves it, as one undo step.
        session.pointer(at, 0, 0, false); session.pointer(far, 0, 0, true); session.finishStroke(); session.release();
        assertEquals(0, session.board().getHex(at).getLevel()); assertEquals(3, session.board().getHex(far).getLevel());
        send(session, Action.UNDO, "", "");
        assertEquals(3, session.board().getHex(at).getLevel()); assertEquals(0, session.board().getHex(far).getLevel());

        // The same for an object: the first press selects it in place, a press on the selected object drags it.
        send(session, Action.CLEAR_SELECTION, "", "");
        session.pointer(east, 0, 0, false, "car"); session.pointer(east, .9, 0, true, "car"); session.finishStroke(); session.release();
        assertEquals(0, placedObjects(session).get(new BoardEditorSession.Selection(east, "car")).x());
        assertEquals(java.util.Set.of("car"), ids(session));
        session.pointer(east, 0, 0, false, "car"); session.pointer(east, .9, 0, true, "car"); session.finishStroke(); session.release();
        assertFalse(placedObjects(session).containsKey(new BoardEditorSession.Selection(east, "car")), "The selected car moved east");

        // A box takes the objects it covers, or the hexes when it covers none; Shift adds and Ctrl removes the
        // selection's own kind.
        send(session, Action.UNDO, "", "");
        BoardEditorSession.Selection car = new BoardEditorSession.Selection(east, "car"), van = new BoardEditorSession.Selection(far, "van");
        BoardEditorSession.Selection hexAt = new BoardEditorSession.Selection(at, ""), hexFar = new BoardEditorSession.Selection(far, "");
        session.select(java.util.List.of(hexAt, hexFar, car), BoardEditorSession.SelectMode.REPLACE);
        assertEquals(java.util.List.of(car), session.snapshot().selection());
        session.select(java.util.List.of(hexAt, hexFar), BoardEditorSession.SelectMode.REPLACE);
        assertEquals(java.util.List.of(hexAt, hexFar), session.snapshot().selection(), "A box over no object selects its hexes");
        session.select(java.util.List.of(car), BoardEditorSession.SelectMode.ADD);
        assertEquals(java.util.List.of(hexAt, hexFar), session.snapshot().selection(), "Shift adds only hexes to hexes");
        session.select(java.util.List.of(hexAt, van), BoardEditorSession.SelectMode.REMOVE);
        assertEquals(java.util.List.of(hexFar), session.snapshot().selection(), "Ctrl removes the covered hexes");
        assertEquals(far, session.snapshot().selected(), "The inspector follows what stays selected");
        session.select(java.util.List.of(car), BoardEditorSession.SelectMode.REPLACE);
        session.select(java.util.List.of(hexFar, van), BoardEditorSession.SelectMode.ADD);
        assertEquals(java.util.Set.of("car", "van"), ids(session));
        session.select(java.util.List.of(car), BoardEditorSession.SelectMode.REMOVE);
        assertEquals(java.util.List.of(van), session.snapshot().selection());
        session.select(java.util.List.of(van), BoardEditorSession.SelectMode.REMOVE);
        assertTrue(session.snapshot().selection().isEmpty()); assertEquals("", session.snapshot().object());

        // Shift drags never move; a Shift click still toggles.
        session.pointer(east, 0, 0, false, "car", true); session.pointer(east, .9, 0, true, "car", true); session.finishStroke();
        assertEquals(java.util.Set.of("car"), ids(session));
        assertEquals(0, placedObjects(session).get(car).x());
    }

    @Test void issuesFollowCommittedEditsUndoAndRedoAndAnIssueSelectsItsHex() throws Exception {
        var session = session();
        assertTrue(session.snapshot().issues().isEmpty(), "A new board has no issues");
        Coords east = new Coords(2, 1);
        int toward = java.util.stream.IntStream.range(0, 6).filter(d -> east.translated(d).equals(at)).findFirst().orElseThrow();
        // Set directly, without an edit: only the neighbour re-validation of the later edits can find east's issue.
        session.board().setHex(east, new megamek.common.Hex(0, "building:1:" + (1 << toward) + ";bldg_elev:1;bldg_cf:15", ""));

        send(session, Action.TERRAIN, "building", "2");
        assertEquals(java.util.Set.of(at, east), session.snapshot().issues().stream().map(megamek.common.board.HexIssue::coords)
              .collect(java.util.stream.Collectors.toSet()), "The edited hex and its re-validated neighbour");
        assertTrue(session.snapshot().issues().stream().filter(issue -> issue.coords().equals(at)).findFirst().orElseThrow()
              .text().startsWith("Incomplete Building"), "The game's own rule text");
        send(session, Action.TERRAIN, "bldg_elev", "1");
        send(session, Action.TERRAIN, "bldg_cf", "40");
        assertEquals(java.util.List.of(new megamek.common.board.HexIssue(east, "",
              "Building has an exit to a building of another Building Type (Light, Medium...).")), session.snapshot().issues(),
              "Completing the building leaves the neighbour's exit issue");
        var published = session.snapshot().issues();
        session.pointer(new Coords(9, 9), 0, 0, false); session.finishStroke();
        assertSame(published, session.snapshot().issues(), "Selection and other non-edits publish the same list");

        send(session, Action.UNDO, "", "");
        assertEquals(java.util.Set.of(at, east), session.snapshot().issues().stream().map(megamek.common.board.HexIssue::coords)
              .collect(java.util.stream.Collectors.toSet()), "Undo re-validates");
        send(session, Action.UNDO, "", ""); send(session, Action.UNDO, "", "");
        assertTrue(session.snapshot().issues().isEmpty());
        send(session, Action.REDO, "", ""); send(session, Action.REDO, "", ""); send(session, Action.REDO, "", "");
        assertEquals(published, session.snapshot().issues(), "Redo re-validates");

        // An issue selects its hex, and the object when it is about one: a deck object whose bridge was removed.
        Coords deck = new Coords(5, 5);
        session.pointer(deck, 0, 0, false); session.finishStroke();
        send(session, Action.ADD_COMPONENT, "", "bridge");
        send(session, Action.ASSET, "", "vegetation/birch-young");
        session.pointer(deck, 0, 0, false, null, false, "bridge"); session.finishStroke();
        String tree = session.snapshot().object();
        assertTrue(session.snapshot().issues().stream().noneMatch(issue -> issue.coords().equals(deck)));
        send(session, Action.REMOVE_COMPONENT, "", "bridge");
        var issue = session.snapshot().issues().stream().filter(found -> found.coords().equals(deck)).findFirst().orElseThrow();
        assertEquals(new megamek.common.board.HexIssue(deck, tree, "Object on deck but no bridge"), issue);
        send(session, Action.CLEAR_SELECTION, "", ""); send(session, Action.TOOL, "", "PAINT");
        send(session, Action.SELECT_AT, deck.getX() + "," + deck.getY(), issue.object());
        assertEquals(deck, session.snapshot().selected()); assertEquals(tree, session.snapshot().object());
        assertEquals(java.util.List.of(new BoardEditorSession.Selection(deck, tree)), session.snapshot().selection());
        assertEquals(BoardEditorSession.Tool.SELECT, session.snapshot().tool());

        // Opening a document validates all of it: here an object whose art is missing. Rail and maglev route markers
        // have no model of their own (the route builder draws the track), so they are not missing art.
        var board = megamek.common.board.Board.createEmptyBoard(4, 4);
        board.getHex(2, 3).setDecorations(java.util.List.of(new megamek.common.board.BoardDecoration("gone", "prop", "scenery/no-such-art",
              null, 0, 0, 0, false, 1, megamek.common.board.BoardDecoration.Placement.ground(), 0)));
        for (String route : java.util.List.of("scenery/rail/route", "scenery/maglev/route")) {
            board.getHex(route.contains("rail") ? 1 : 0, 1).setDecorations(java.util.List.of(new megamek.common.board.BoardDecoration(
                  route, "prop", route, null, 0, 0, 0, false, 1, megamek.common.board.BoardDecoration.Placement.ground(), 0)));
        }
        Path file = directory.resolve("missing.board2"); BoardFile.save(board, file);
        session.open(file);
        assertEquals(java.util.List.of(new megamek.common.board.HexIssue(new Coords(2, 3), "gone", "Object art not found: scenery/no-such-art")),
              session.snapshot().issues());
    }

    /** A road or bridge that does not join up is an issue with its recommended fix; games do not refuse it. */
    @Test void aRoadIntoNothingIsAnIssueWithItsFixButNoRulesError() throws Exception {
        var board = megamek.common.board.Board.createEmptyBoard(3, 3);
        Coords road = new Coords(1, 1);
        board.setHex(road, new Hex(0, "road:1:1", ""));
        Path file = directory.resolve("road.board2"); BoardFile.save(board, file);
        var session = new BoardEditorSession();
        session.open(file);
        assertEquals(java.util.List.of(new megamek.common.board.HexIssue(road, "",
              "Road at 0202 exits N into 0201, which has no road, pavement, bridge or building\n"
                    + "Recommended fix: Remove this road's N exit, or continue the road into 0201")), session.snapshot().issues());
        assertEquals(Messages.getString("BoardEditor.opened", "road.board2"), session.snapshot().message());
    }

    private Map<BoardEditorSession.Selection, megamek.common.board.BoardDecoration> placedObjects(BoardEditorSession session) {
        var result = new java.util.LinkedHashMap<BoardEditorSession.Selection, megamek.common.board.BoardDecoration>();
        for (int x = 0; x < session.board().getWidth(); x++) {
            for (int y = 0; y < session.board().getHeight(); y++) {
                Coords coords = new Coords(x, y);
                for (var object : session.board().getHex(coords).getDecorations()) {
                    result.put(new BoardEditorSession.Selection(coords, object.id()), object);
                }
            }
        }
        return result;
    }

    @Test void movingHexGroupsHandlesOverlappingDestinationsAndRejectsPartialOutOfBoundsMoves() {
        var session = session();
        Coords second = at.translated(3), third = second.translated(3);
        send(session, Action.ELEVATION, "", "2");
        session.pointer(second, 0, 0, false); session.finishStroke(); send(session, Action.ELEVATION, "", "4");
        session.pointer(at, 0, 0, false); session.finishStroke();
        session.pointer(second, 0, 0, false, null, true); session.finishStroke();
        session.pointer(at, 0, 0, false); session.pointer(second, 0, 0, true);
        assertEquals(2, session.snapshot().movePreview().size());
        assertEquals(2, session.board().getHex(at).getLevel(), "Whole-hex movement is previewed until release");
        session.finishStroke();
        assertEquals(0, session.board().getHex(at).getLevel()); assertEquals(2, session.board().getHex(second).getLevel());
        assertEquals(4, session.board().getHex(third).getLevel());
        send(session, Action.UNDO, "", "");
        assertEquals(2, session.board().getHex(at).getLevel()); assertEquals(4, session.board().getHex(second).getLevel());
        assertEquals(0, session.board().getHex(third).getLevel());
        session.pointer(at, 0, 0, false); session.finishStroke();
        session.pointer(second, 0, 0, false, null, true); session.finishStroke();
        session.pointer(at, 0, 0, false); session.pointer(new Coords(15, 16), 0, 0, true); session.finishStroke();
        assertEquals(2, session.board().getHex(at).getLevel()); assertEquals(4, session.board().getHex(second).getLevel());
        assertTrue(session.snapshot().message().contains("whole selection"));
    }

    @Test void contextualFoliageCliffsAndElevatorsUseValidGameTerrain() {
        var session = session();
        send(session, Action.ADD_COMPONENT, "", "vegetation");
        send(session, Action.TERRAIN, "foliage_elev", "3");
        assertEquals(2, session.board().getHex(at).terrainLevel(Terrains.FOLIAGE_ELEV), "Light woods cannot use ultra-heavy canopy height");
        send(session, Action.TERRAIN, "jungle", "3");
        send(session, Action.TERRAIN, "foliage_elev", "2");
        assertEquals(3, session.board().getHex(at).terrainLevel(Terrains.FOLIAGE_ELEV), "Ultra-heavy jungle requires the rules' canopy height");
        var hex = session.board().getHex(at);
        assertFalse(hex.containsTerrain(Terrains.WOODS));
        assertEquals(3, hex.terrainLevel(Terrains.FOLIAGE_ELEV));
        assertTrue(hex.isValid(null));
        send(session, Action.TERRAIN, "industrial_elevator", "-2");
        send(session, Action.ELEVATOR, "top", "4");
        send(session, Action.ELEVATOR, "capacity", "180");
        var terrain = session.board().getHex(at).getTerrain(Terrains.INDUSTRIAL_ELEVATOR);
        var elevator = megamek.common.IndustrialElevator.fromTerrain(megamek.common.board.BoardLocation.of(at, 0), terrain.getLevel(), terrain.getExits());
        assertEquals(4, elevator.getShaftTop()); assertEquals(180, elevator.getCapacityTons());
        send(session, Action.ELEVATION, "", "3");
        send(session, Action.EDGE, "cliff_top", "0");
        assertFalse(session.board().getHex(at).containsTerrain(Terrains.CLIFF_TOP));
        send(session, Action.ELEVATION, "", "2");
        send(session, Action.EDGE, "cliff_top", "0");
        assertEquals(1, session.board().getHex(at).getTerrain(Terrains.CLIFF_TOP).getExits());
    }

    @Test void undoRestoresNeighborCliffEdgesAfterElevationChanges() {
        var session = session();
        send(session, Action.ELEVATION, "", "2");
        send(session, Action.EDGE, "cliff_top", "0");
        Coords neighbor = at.translated(0);
        session.pointer(neighbor, 0, 0, false);
        send(session, Action.ELEVATION, "", "3");
        assertFalse(session.board().getHex(at).containsTerrain(Terrains.CLIFF_TOP));
        send(session, Action.UNDO, "", "");
        assertEquals(0, session.board().getHex(neighbor).getLevel());
        assertEquals(1, session.board().getHex(at).getTerrain(Terrains.CLIFF_TOP).getExits());
        assertTrue(session.board().getHex(neighbor).containsTerrain(Terrains.CLIFF_BOTTOM));
        send(session, Action.REDO, "", "");
        assertFalse(session.board().getHex(at).containsTerrain(Terrains.CLIFF_TOP));
    }

    @Test void liveNumericDragIsOneUndoStepAndRetainsItsSurfaceReceiver() {
        var session = session();
        send(session, Action.ASSET, "", "vegetation/birch-young"); session.pointer(at, .2, .1, false); session.finishStroke();
        var original = session.snapshot().objects().getFirst();
        for (String value : java.util.List.of("1.1", "1.8", "3.5")) {
            session.command(new Command(Action.OBJECT_VALUE, "offset", value), null, true);
        }
        assertEquals(3.5, session.snapshot().objects().getFirst().placement().offset());
        assertEquals("ground", session.snapshot().objects().getFirst().placement().receiver().terrain());
        session.finishStroke(); send(session, Action.UNDO, "", "");
        assertEquals(original, session.snapshot().objects().getFirst());
        assertEquals(original.id(), session.snapshot().object(), "Undoing a value keeps its inspector selected");
        send(session, Action.REDO, "", "");
        assertEquals(3.5, session.snapshot().objects().getFirst().placement().offset());
        assertEquals(original.id(), session.snapshot().object());
        send(session, Action.UNDO, "", ""); send(session, Action.UNDO, "", "");
        assertTrue(session.snapshot().object().isEmpty(), "Undoing placement clears the removed selection");
    }

    @Test void stretchIsALiveObjectValueThatGroupEditsDuplicatesAndSavingKeep() throws Exception {
        var session = session();
        send(session, Action.ASSET, "", "scenery/vehicles/car"); session.pointer(at, .2, .1, false); session.finishStroke();
        var original = session.snapshot().objects().getFirst();
        for (String value : java.util.List.of("1.2", "1.6", "2")) {
            session.command(new Command(Action.OBJECT_VALUE, "stretchX", value), null, true);
        }
        session.finishStroke();
        send(session, Action.OBJECT_VALUE, "stretchZ", "0.5");
        var stretched = session.snapshot().objects().getFirst();
        assertEquals(new megamek.common.board.BoardDecoration.Stretch(2, 1, .5), stretched.stretch());
        assertEquals(original.scale(), stretched.scale(), "Stretch multiplies the uniform scale, it does not replace it");
        send(session, Action.OBJECT_VALUE, "stretchY", "0");
        assertEquals(stretched, session.snapshot().objects().getFirst(), "A stretch factor must be positive");
        send(session, Action.UNDO, "", "");
        assertEquals(new megamek.common.board.BoardDecoration.Stretch(2, 1, 1), session.snapshot().objects().getFirst().stretch());
        send(session, Action.UNDO, "", "");
        assertEquals(original, session.snapshot().objects().getFirst(), "The live stretch drag was one undo step");
        send(session, Action.REDO, "", ""); send(session, Action.REDO, "", "");
        assertEquals(stretched, session.snapshot().objects().getFirst());

        // Group edits keep each member's stretch on its own axes; a group's scale stays uniform.
        session.board().getHex(at).setDecorations(java.util.List.of(stretched.withGroup("g"), prop("other", -.2, -.1).withGroup("g")));
        send(session, Action.SELECT_OBJECT, "g", "");
        send(session, Action.GROUP_VALUE, "scale", "2");
        send(session, Action.GROUP_VALUE, "rotation", "90");
        send(session, Action.GROUP_VALUE, "mirror", "true");
        var member = placedObjects(session).values().stream().filter(o -> o.id().equals(stretched.id())).findFirst().orElseThrow();
        assertEquals(stretched.stretch(), member.stretch());
        assertEquals(2 * stretched.scale(), member.scale(), 1e-9);
        send(session, Action.SELECT_OBJECT, "g", "");
        send(session, Action.DUPLICATE_OBJECT, "", "");
        assertEquals(2, placedObjects(session).values().stream().filter(o -> o.stretch().equals(stretched.stretch())).count());

        Path target = directory.resolve("stretch.board2"); session.save(target);
        var saved = placedObjects(session).values().stream().filter(o -> o.id().equals(stretched.id())).findFirst().orElseThrow();
        var loaded = BoardFile.read(target);
        assertEquals(saved.stretch(), java.util.stream.IntStream.range(0, loaded.getWidth()).boxed()
              .flatMap(x -> java.util.stream.IntStream.range(0, loaded.getHeight()).mapToObj(y -> loaded.getHex(x, y)))
              .flatMap(hex -> hex.getDecorations().stream()).filter(o -> o.id().equals(saved.id())).findFirst().orElseThrow()
              .stretch());
    }

    @Test void slotColoursAreObjectValuesThatDuplicatesAndUndoKeep() {
        var session = session();
        // A pool recoloured in all four of its slots.
        send(session, Action.ASSET, "", "scenery/pools/freeform"); session.pointer(at, .2, .1, false); session.finishStroke();
        var colours = java.util.List.of("#63753d", "#63753d", "#63753d", "#94c7ab");
        for (int slot = 0; slot < 4; slot++) { send(session, Action.OBJECT_VALUE, "colour" + slot, colours.get(slot)); }
        var pond = session.snapshot().objects().getFirst();
        assertEquals(colours, pond.colours().slots());
        send(session, Action.OBJECT_VALUE, "colour3", "#E0F2FA");
        var clear = session.snapshot().objects().getFirst();
        assertEquals("#e0f2fa", clear.colours().slot(3));
        assertEquals(pond.colours().slot(0), clear.colours().slot(0), "Other slots keep their colours");
        send(session, Action.OBJECT_VALUE, "colour0", "teal");
        assertEquals(clear, session.snapshot().objects().getFirst(), "A colour is #rrggbb");
        send(session, Action.DUPLICATE_OBJECT, "", "");
        assertEquals(2, session.snapshot().objects().stream().filter(o -> o.colours().equals(clear.colours())).count());
        send(session, Action.UNDO, "", ""); send(session, Action.UNDO, "", "");
        assertEquals(pond, session.snapshot().objects().getFirst(), "Each colour choice is one undo step");
        send(session, Action.SELECT_OBJECT, "", pond.id());
        for (int slot = 0; slot < 4; slot++) { send(session, Action.OBJECT_VALUE, "colour" + slot, ""); }
        assertEquals(megamek.common.board.BoardDecoration.Colours.NONE, session.snapshot().objects().getFirst().colours(),
              "Empty returns a slot to the model's own colour");
    }

    @Test void globalThemeIsUndoableWithoutLosingHexOverridesOrObjects() {
        var session = session();
        send(session, Action.ASSET, "", "vegetation/birch-young"); session.pointer(at, .2, .1, false); session.finishStroke();
        var objects = session.snapshot().objects();
        send(session, Action.THEME, "", "snow");
        send(session, Action.MAP_THEME, "", "desert");
        assertEquals("desert", session.board().getHex(at).getTheme());
        assertEquals("desert", session.board().getHex(0, 0).getTheme());
        send(session, Action.UNDO, "", "");
        assertEquals("snow", session.board().getHex(at).getTheme());
        assertNotEquals("desert", session.board().getHex(0, 0).getTheme());
        assertEquals(objects, session.snapshot().objects());
    }

    @Test void sculptStrokeMovesEachEnteredHexOnceAsOneUndoStepAndCtrlInverts() {
        var session = session();
        Coords first = new Coords(4, 4), second = new Coords(6, 4);
        send(session, Action.TOOL, "", "SCULPT");
        session.pointer(first, 0, 0, false);
        session.pointer(second, 0, 0, true); session.pointer(second, 0, 0, true); session.pointer(first, 0, 0, true);
        session.finishStroke();
        assertEquals(1, session.board().getHex(first).getLevel());
        assertEquals(1, session.board().getHex(second).getLevel(), "Re-entering a hex in one stroke does not raise it again");
        assertEquals("Raise · 1 hex", session.sculptHint(first, 1));
        send(session, Action.UNDO, "", "");
        assertEquals(0, session.board().getHex(first).getLevel());
        assertEquals(0, session.board().getHex(second).getLevel());
        assertFalse(session.snapshot().canUndo(), "The whole drag is a single undo step");
        session.pointer(first, 0, 0, false, null, false, "ground", true); session.finishStroke();
        assertEquals(-1, session.board().getHex(first).getLevel(), "Ctrl lowers in Raise mode");
        send(session, Action.BRUSH_VALUE, "sculpt", "LEVEL");
        assertEquals("Level to L0", session.sculptHint(second, 1));
        session.pointer(first, 0, 0, false); session.pointer(second, 0, 0, true); session.finishStroke();
        assertEquals(-1, session.board().getHex(second).getLevel(), "Level mode uses the first hex of the stroke");
    }

    @Test void ctrlWheelIgnoresSculptSlope() {
        var session = session();
        send(session, Action.TOOL, "", "SCULPT");
        Coords centre = new Coords(6, 6);
        for (int notch = 0; notch < 3; notch++) { session.adjustElevation(centre, 1); }
        session.finishStroke();
        assertEquals(3, session.board().getHex(centre).getLevel());
        centre.allAdjacent().forEach(at -> assertEquals(0, session.board().getHex(at).getLevel()));
        centre.allAtDistance(2).forEach(at -> assertEquals(0, session.board().getHex(at).getLevel()));
        centre.allAtDistance(3).forEach(at -> assertEquals(0, session.board().getHex(at).getLevel()));
        send(session, Action.UNDO, "", "");
        centre.allAtDistanceOrLess(2).forEach(at -> assertEquals(0, session.board().getHex(at).getLevel()));
        send(session, Action.BRUSH_VALUE, "slope", "false");
        session.adjustElevation(centre, 2); session.finishStroke();
        assertEquals(2, session.board().getHex(centre).getLevel());
        centre.allAdjacent().forEach(at -> assertEquals(0, session.board().getHex(at).getLevel()));
    }

    @Test void ctrlWheelDirectionIgnoresSculptMode() {
        Coords centre = new Coords(6, 6);
        for (var mode : LevelSculpt.Mode.values()) {
            var session = session();
            send(session, Action.TOOL, "", "SCULPT");
            send(session, Action.BRUSH_VALUE, "sculpt", mode.name());
            session.adjustElevation(centre, -3); session.finishStroke();
            assertEquals(-3, session.board().getHex(centre).getLevel(), mode.name());
            centre.allAdjacent().forEach(at -> assertEquals(0, session.board().getHex(at).getLevel()));
            session.adjustElevation(centre, 6); session.finishStroke();
            assertEquals(3, session.board().getHex(centre).getLevel(), mode.name());
            centre.allAdjacent().forEach(at -> assertEquals(0, session.board().getHex(at).getLevel()));
        }
    }

    @Test void fastSculptDragCoversSkippedHexes() {
        var session = session();
        Coords from = new Coords(2, 4), to = new Coords(7, 4);
        send(session, Action.TOOL, "", "SCULPT");
        send(session, Action.BRUSH_VALUE, "slope", "false");
        session.pointer(from, 0, 0, false); session.pointer(to, 0, 0, true); session.finishStroke();
        Coords.intervening(from, to).forEach(at -> assertEquals(1, session.board().getHex(at).getLevel(), "Ridge at " + at));
    }

    @Test void sculptKeepsWaterBridgeAndBuildingTerrainAndUndoRestoresSlopedNeighbours() {
        var session = session();
        send(session, Action.TOOL, "", "SCULPT");
        Coords centre = new Coords(4, 4), water = centre.translated(0), bridge = centre.translated(2), building = centre.translated(4);
        var board = session.board();
        board.setHex(centre, new megamek.common.Hex(0, "water:1", ""));
        board.setHex(bridge, new megamek.common.Hex(0, "bridge:1:9;bridge_cf:40;bridge_elev:1", ""));
        board.setHex(building, new megamek.common.Hex(0, "building:1;bldg_cf:15;bldg_elev:1", ""));
        board.setHex(water, new megamek.common.Hex(0, "water:1", ""));
        session.pointer(centre, 0, 0, false); session.finishStroke();
        session.pointer(centre, 0, 0, false); session.finishStroke();
        assertEquals(1, session.board().getHex(water).getLevel(), "Slope moves a neighbouring water surface too");
        assertEquals(1, session.board().getHex(centre).terrainLevel(Terrains.WATER));
        assertEquals(1, session.board().getHex(bridge).terrainLevel(Terrains.BRIDGE_ELEV));
        assertEquals(1, session.board().getHex(building).terrainLevel(Terrains.BLDG_ELEV));
        send(session, Action.UNDO, "", "");
        send(session, Action.UNDO, "", "");
        for (Coords hex : java.util.List.of(centre, water, bridge, building)) { assertEquals(0, session.board().getHex(hex).getLevel()); }
    }

    /** Settings → Map → Arena: the tag that random map selection reads, not an undo step, saved with the board. */
    @Test void arenaTogglesTheArenaTagMarksTheDocumentDirtyAndIsSaved() throws Exception {
        var session = session();
        Path file = directory.resolve("arena.board2");
        session.save(file);
        send(session, Action.BOARD, "arena", "true");
        assertTrue(session.snapshot().arena());
        assertTrue(session.dirty());
        assertFalse(session.snapshot().canUndo(), "A board setting is its own undo, as in the old editor");
        session.save(file);
        assertTrue(BoardFile.metadata(file).tags().contains("Arena"));
        send(session, Action.BOARD, "arena", "false");
        assertFalse(session.snapshot().arena());
        assertTrue(session.dirty());
        session.save(file);
        assertFalse(BoardFile.metadata(file).tags().contains("Arena"));
    }

    /** Manual tags are added and removed; Update automatic tags replaces only the "(Auto)" ones, by the tagger's rule. */
    @Test void manualTagsStayWhileUpdateAutomaticTagsFollowsTheBoard() throws Exception {
        var session = session();
        session.save(directory.resolve("tags.board2"));
        send(session, Action.BOARD, "tag", "  Hills of Home ");
        assertEquals(List.of("Hills of Home"), session.snapshot().tags());
        assertTrue(session.dirty());
        send(session, Action.BOARD, "tag", "Woods (Auto)");
        assertEquals(Messages.getString("BoardEditor.tagAutomatic"), session.snapshot().message());
        assertEquals(List.of("Hills of Home"), session.snapshot().tags(), "Automatic tags come only from the tagger's rule");
        session.board().addTag("Stale (Auto)");
        send(session, Action.CHOOSE_BRUSH, "vegetation", "woods-1");
        send(session, Action.BRUSH_VALUE, "terrain:woods", "2");
        send(session, Action.BRUSH, "", "4");
        session.pointer(new Coords(8, 8), 0, 0, false); session.finishStroke();
        send(session, Action.BOARD, "autotags", "");
        var expected = new java.util.TreeSet<>(BoardsTagger.tagsFor(session.board()));
        expected.add("Hills of Home");
        assertEquals(List.copyOf(expected), session.snapshot().tags(), "The stale automatic tag goes, the manual one stays");
        send(session, Action.BOARD, "untag", "Hills of Home");
        assertFalse(session.snapshot().tags().contains("Hills of Home"));
        assertTrue(session.snapshot().tags().stream().allMatch(BoardsTagger::isAutoTag));
    }

    /**
     * The Industrial elevator card paints a shaft from the surface that carries 100 t. Its bottom above its top breaks
     * the shared rule: the editor refuses such a value, and a board that holds one anyway lists it; it saves (decision
     * P), but a game's strict read refuses the file.
     */
    @Test void industrialElevatorCardAndTheSharedShaftRule() throws Exception {
        var session = session();
        send(session, Action.CHOOSE_BRUSH, "elevators", "industrial");
        session.pointer(at, 0, 0, false); session.finishStroke();
        Terrain shaft = session.board().getHex(at).getTerrain(Terrains.INDUSTRIAL_ELEVATOR);
        assertEquals(0, shaft.getLevel());
        assertEquals(0, IndustrialElevator.shaftTop(shaft.getExits()));
        assertEquals(100, IndustrialElevator.capacityTons(shaft.getExits()));
        send(session, Action.TOOL, "", "SELECT");
        send(session, Action.TERRAIN, "industrial_elevator", "2");
        assertTrue(session.snapshot().message().contains("shaft bottom (2) is above the shaft top (0)"), session.snapshot().message());
        assertEquals(0, session.board().getHex(at).terrainLevel(Terrains.INDUSTRIAL_ELEVATOR));
        send(session, Action.ELEVATOR, "top", "3");
        send(session, Action.TERRAIN, "industrial_elevator", "2");
        assertEquals(2, session.board().getHex(at).terrainLevel(Terrains.INDUSTRIAL_ELEVATOR));
        send(session, Action.ELEVATOR, "top", "1");
        assertEquals(3, IndustrialElevator.shaftTop(session.board().getHex(at).getTerrain(Terrains.INDUSTRIAL_ELEVATOR).getExits()),
              "A top below the bottom is refused too");

        Coords old = new Coords(5, 5);
        session.board().setHex(old, new Hex(0, "industrial_elevator:3:266", ""));
        session.validateAll();
        assertTrue(session.snapshot().issues().stream().anyMatch(issue -> issue.coords().equals(old)
              && issue.text().contains("shaft bottom (3) is above the shaft top (1)")), session.snapshot().issues().toString());
        Path saved = directory.resolve("shaft.board2");
        session.save(saved);
        assertThrows(java.io.IOException.class, () -> BoardFile.read(saved));
    }

    /** The old editor's Flood: submerged hexes get water up to the surface, the rest stays; one Undo restores it all. */
    @Test void floodSubmergesOnlyHexesBelowTheSurfaceInOneUndoStep() {
        var session = session();
        Coords low = new Coords(2, 2), wet = new Coords(4, 4), high = new Coords(6, 6);
        session.board().setHex(low, new Hex(-2));
        session.board().setHex(wet, new Hex(-1, "water:1", ""));
        session.board().setHex(high, new Hex(1, "woods:1;foliage_elev:2", ""));
        Map<Coords, Object> before = nodes(session, low, wet, high, at);
        send(session, Action.FLOOD, "", "0");
        assertEquals(0, session.board().getHex(low).getLevel());
        assertEquals(2, session.board().getHex(low).terrainLevel(Terrains.WATER), "An L-2 hex becomes depth 2 at L0");
        assertEquals(0, session.board().getHex(wet).getLevel());
        assertEquals(2, session.board().getHex(wet).terrainLevel(Terrains.WATER), "Depth 1 at L-1 becomes depth 2");
        assertEquals(before.get(high), BoardFile.hexNode(session.board().getHex(high)), "A hex above the surface stays");
        assertEquals(before.get(at), BoardFile.hexNode(session.board().getHex(at)), "A hex at the surface stays");
        send(session, Action.UNDO, "", "");
        assertEquals(before, nodes(session, low, wet, high, at));
        assertFalse(session.snapshot().canUndo(), "Flood was one undo step");
    }

    /** Your flooded city: the ground stays, the water rises two levels, and a deck one level over the ground ends up under it. */
    @Test void floodLeavesADeckOverTheSameGround() {
        var session = session();
        Coords street = new Coords(2, 2);
        session.board().setHex(street, new Hex(-2, "bridge:1:9;bridge_cf:40;bridge_elev:1", ""));
        send(session, Action.FLOOD, "", "0");
        Hex flooded = session.board().getHex(street);
        assertEquals(2, flooded.terrainLevel(Terrains.WATER));
        assertEquals(-1, flooded.terrainLevel(Terrains.BRIDGE_ELEV));
        assertTrue(flooded.isValid(null), "A deck may lie under water");
    }

    /**
     * Flood with Remove clears the submerged hexes but keeps a bridge where it is (with the objects on its deck, its height
     * now from the new surface) and the deployment zones, which are scenario data.
     */
    @Test void floodRemoveKeepsDecksAndTheDeploymentZones() {
        var session = session();
        Coords woods = new Coords(2, 2), building = new Coords(4, 4), bridge = new Coords(6, 6), sunk = new Coords(8, 8);
        session.board().setHex(woods, new Hex(-1, "woods:1;foliage_elev:2;deployment_zone:0:5", ""));
        session.board().setHex(building, new Hex(-1, "building:1;bldg_cf:15;bldg_elev:1", ""));
        Hex deck = new Hex(-2, "bridge:1:9;bridge_cf:40;bridge_elev:3", "");
        deck.setDecorations(List.of(prop("on-deck", 0, 0).transform(0, 0, 0, false, 1, BoardDecoration.Placement.surface("bridge", "deck", 0)),
              prop("on-ground", .1, .1)));
        session.board().setHex(bridge, deck);
        session.board().setHex(sunk, new Hex(-3, "bridge:1:9;bridge_cf:40;bridge_elev:1", ""));
        Map<Coords, Object> before = nodes(session, woods, building, bridge, sunk);
        send(session, Action.FLOOD, "remove", "0");
        Hex flooded = session.board().getHex(woods);
        assertFalse(flooded.containsAnyTerrainOf(Terrains.WOODS, Terrains.FOLIAGE_ELEV));
        assertEquals(1, flooded.terrainLevel(Terrains.WATER));
        assertEquals(5, flooded.getTerrain(Terrains.DEPLOYMENT_ZONE).getExits(), "Zones 1 and 3 stay");
        assertFalse(session.board().getHex(building).containsAnyTerrainOf(Terrains.BUILDING, Terrains.BLDG_CF, Terrains.BLDG_ELEV));
        Hex kept = session.board().getHex(bridge);
        assertTrue(kept.containsTerrain(Terrains.BRIDGE));
        assertEquals(1, kept.terrainLevel(Terrains.BRIDGE_ELEV), "The deck stays at L1, now one level above the surface");
        assertEquals(List.of("on-deck"), kept.getDecorations().stream().map(BoardDecoration::id).toList());
        assertEquals(-2, session.board().getHex(sunk).terrainLevel(Terrains.BRIDGE_ELEV), "A deck the water rises past stays at L-2");
        send(session, Action.UNDO, "", "");
        assertEquals(before, nodes(session, woods, building, bridge, sunk));
    }

    /**
     * Raise/Lower Board moves every hex and everything at a fixed level in it (the Solaris elevator's alternate level, an
     * absolute route marker); relative heights stay and a manual cliff edge survives. Flatten puts every hex at L0.
     */
    @Test void boardLevelChangeMovesFixedLevelsAndFlattenLevelsTheBoard() {
        var session = session();
        send(session, Action.ELEVATION, "", "2");
        send(session, Action.EDGE, "cliff_top", "0");
        Coords building = new Coords(2, 2), water = new Coords(4, 4), elevator = new Coords(6, 6), route = new Coords(8, 8);
        session.board().setHex(building, new Hex(1, "building:1;bldg_cf:15;bldg_elev:2", ""));
        session.board().setHex(water, new Hex(0, "water:2", ""));
        session.board().setHex(elevator, new Hex(0, "solaris_elevator:3:63", ""));
        Hex routed = new Hex(0);
        routed.setDecorations(List.of(new BoardDecoration("marker", "prop", megamek.common.board.RailRoute.Kind.MAGLEV.asset, null, 0, 0, 0,
              false, 1, BoardDecoration.Placement.absolute(4), 0, false, 0, 0, null), prop("standing", .2, .2)));
        session.board().setHex(route, routed);
        Map<Coords, Object> before = nodes(session, building, water, elevator, route, at, new Coords(0, 0));
        send(session, Action.BOARD_LEVEL, "by", "2");
        assertEquals(2, session.board().getHex(0, 0).getLevel());
        assertEquals(3, session.board().getHex(building).getLevel());
        assertEquals(2, session.board().getHex(building).terrainLevel(Terrains.BLDG_ELEV), "Heights are relative");
        assertEquals(2, session.board().getHex(water).terrainLevel(Terrains.WATER), "Depths are relative");
        assertEquals(5, session.board().getHex(elevator).terrainLevel(Terrains.SOLARIS_ELEVATOR), "The alternate level is absolute");
        var objects = session.board().getHex(route).getDecorations();
        assertEquals(6, objects.getFirst().placement().level(), "An absolute marker at L4 moves to L6");
        assertEquals(BoardDecoration.Placement.ground(), objects.get(1).placement(), "A surface prop keeps its placement");
        assertEquals(1, session.board().getHex(at).getTerrain(Terrains.CLIFF_TOP).getExits(), "The manual cliff edge survives");
        send(session, Action.BOARD_LEVEL, "to", "0");
        for (int x = 0; x < session.board().getWidth(); x++) {
            for (int y = 0; y < session.board().getHeight(); y++) { assertEquals(0, session.board().getHex(x, y).getLevel()); }
        }
        assertEquals(3, session.board().getHex(elevator).terrainLevel(Terrains.SOLARIS_ELEVATOR));
        assertEquals(4, session.board().getHex(route).getDecorations().getFirst().placement().level());
        send(session, Action.UNDO, "", "");
        send(session, Action.UNDO, "", "");
        assertEquals(before, nodes(session, building, water, elevator, route, at, new Coords(0, 0)), "Each was one undo step");
    }

    /** Whole-board Remove offers the components on the board and removes one everywhere; Clear blanks every hex. */
    @Test void removeAndClearAreOneUndoStepEach() {
        var session = session();
        send(session, Action.CHOOSE_BRUSH, "vegetation", "woods-1");
        send(session, Action.BRUSH, "", "3");
        session.pointer(new Coords(5, 5), 0, 0, false); session.finishStroke();
        send(session, Action.ASSET, "", "scenery/vehicles/car");
        session.pointer(new Coords(9, 9), 0, 0, false); session.finishStroke();
        send(session, Action.TOOL, "", "SELECT");
        assertTrue(session.snapshot().boardComponents().contains("vegetation"));
        Map<Coords, Object> before = nodes(session, new Coords(5, 5), new Coords(9, 9));
        send(session, Action.REMOVE_COMPONENT, "board", "vegetation");
        assertFalse(session.snapshot().boardComponents().contains("vegetation"));
        assertFalse(session.board().getHex(5, 5).containsAnyTerrainOf(Terrains.WOODS, Terrains.FOLIAGE_ELEV));
        assertEquals(1, session.board().getHex(9, 9).getDecorations().size(), "Remove keeps the objects");
        send(session, Action.UNDO, "", "");
        assertEquals(before, nodes(session, new Coords(5, 5), new Coords(9, 9)));
        send(session, Action.CLEAR, "", "");
        assertEquals(BoardFile.hexNode(new Hex(0)), BoardFile.hexNode(session.board().getHex(5, 5)));
        assertTrue(session.board().getHex(9, 9).getDecorations().isEmpty());
        assertTrue(session.snapshot().boardComponents().isEmpty());
        send(session, Action.UNDO, "", "");
        assertEquals(before, nodes(session, new Coords(5, 5), new Coords(9, 9)));
    }

    /** A legacy road keeps its exit into pavement when opened, as a game of that file has it, and the saved file holds it. */
    @Test void anOpenedLegacyRoadKeepsItsExitsAsStoredOnes() throws Exception {
        Coords road = new Coords(3, 3), pavement = road.translated(0);
        var legacy = megamek.common.board.Board.createEmptyBoard(8, 8);
        legacy.setHex(pavement, new Hex(0, "pavement:1", ""));
        legacy.setHex(road, new Hex(0, "road:1", ""));
        var session = new BoardEditorSession();
        session.open(legacy, null);
        var opened = session.board().getHex(road).getTerrain(Terrains.ROAD);
        assertTrue(opened.hasExitsSpecified());
        assertEquals(1, opened.getExits());
        Path file = directory.resolve("roads.board2");
        session.save(file);
        assertEquals(1, BoardFile.read(file).getHex(road).getTerrain(Terrains.ROAD).getExits());
    }

    /**
     * A water or pavement side turned off in Edit parts both hexes, saves as named directions under one property and no
     * derived sides, and survives reopening; only the same terrain at the same level can be parted, and terrain gone from
     * a hex rejoins its neighbours' sides, so what is painted there again connects.
     */
    @Test void separatedSidesPartBothHexesAndSaveAsNamedDirections() throws Exception {
        for (String terrain : List.of("water", "pavement")) {
            Coords here = new Coords(3, 3), north = here.translated(0), south = here.translated(3);
            var board = megamek.common.board.Board.createEmptyBoard(8, 8);
            for (Coords hex : List.of(here, north, south)) { board.setHex(hex, new Hex(0, terrain + ":1", "")); }
            var session = new BoardEditorSession();
            session.open(board, null);
            send(session, Action.SELECT_AT, "3,3", "");
            assertEquals(9, session.snapshot().joinedSides().get(terrain), terrain + " joins by default");

            send(session, Action.EDGE, terrain, "0");
            assertEquals(8, session.snapshot().joinedSides().get(terrain));
            assertEquals(1, separated(session, here, terrain));
            assertEquals(8, separated(session, north, terrain), "Both hexes of the side");

            Path file = directory.resolve(terrain + "-sides.board2");
            session.save(file);
            String text = Files.readString(file);
            assertTrue(text.contains("\"separated\":[\"n\"]"), text);
            assertFalse(text.contains("connections"), "No derived sides: " + text);
            session.open(file);
            send(session, Action.SELECT_AT, "3,3", "");
            assertEquals(8, session.snapshot().joinedSides().get(terrain), "Reopened");

            send(session, Action.EDGE, terrain, "1");
            assertEquals(Messages.getString("BoardEditor.connectedSidesRefused"), session.snapshot().message());
            assertEquals(1, separated(session, here, terrain));

            send(session, Action.EDGE, terrain, "0");
            assertEquals(9, session.snapshot().joinedSides().get(terrain), "Turned on again");
            assertEquals(0, separated(session, north, terrain));
            send(session, Action.UNDO, "", "");
            assertEquals(1, separated(session, here, terrain), "One undo step, both hexes");
            assertEquals(8, separated(session, north, terrain));

            send(session, Action.SELECT_AT, north.getX() + "," + north.getY(), "");
            send(session, Action.REMOVE_COMPONENT, "", terrain);
            assertEquals(0, separated(session, here, terrain), "Nothing left to part it from");
        }
    }

    /** A legacy file's exits on water choose 2D tiles: they part nothing, and Edit shows the water joined. */
    @Test void legacyWaterExitsAreNotSeparatedSides() {
        Coords here = new Coords(3, 3);
        var board = megamek.common.board.Board.createEmptyBoard(8, 8);
        board.setHex(here, new Hex(0, "water:1:0", ""));
        board.setHex(here.translated(0), new Hex(0, "water:1:0", ""));
        var session = new BoardEditorSession();
        session.open(board, null);
        send(session, Action.SELECT_AT, "3,3", "");
        assertEquals(1, session.snapshot().joinedSides().get("water"));
        assertEquals(0, separated(session, here, "water"));
    }

    private static int separated(BoardEditorSession session, Coords at, String terrain) {
        return megamek.common.board.HexAppearance.separated(session.board().getHex(at).getAppearance(), terrain);
    }

    /** The user's own Open (a Recent Boards entry) and Save add the file to the launcher's Recent Boards list. */
    @Test void openAndSaveAddTheFileToRecentBoards() throws Exception {
        Path list = new java.io.File(megamek.common.Configuration.configDir(), "recent_boards.yml").toPath();
        byte[] kept = Files.exists(list) ? Files.readAllBytes(list) : null;
        try {
            var session = session();
            Path first = directory.resolve("first.board2"), second = directory.resolve("second.board2");
            session.save(first);
            send(session, Action.OPEN, "", first.toString());
            assertEquals(first.toAbsolutePath().normalize().toString(), RecentBoardList.getRecentBoards().getLast());
            session.save(second);
            send(session, Action.SAVE, "", "");
            assertEquals(second.toAbsolutePath().normalize().toString(), RecentBoardList.getRecentBoards().getLast());
        } finally {
            // The developer's own list stays as it was.
            if (kept == null) { Files.deleteIfExists(list); } else { Files.write(list, kept); }
        }
    }

    /** A new board's .board2 gets the license header when the Client Settings ask for it; an imported one keeps its own. */
    @Test void newBoardsGetTheLicenseHeaderAndImportsKeepTheirOwn() throws Exception {
        var preferences = megamek.client.ui.clientGUI.GUIPreferences.getInstance();
        boolean include = preferences.getBoardSaveIncludeLicense();
        try {
            preferences.setValue(megamek.client.ui.clientGUI.GUIPreferences.BOARD_SAVE_INCLUDE_LICENSE, true);
            var session = session();
            Path created = directory.resolve("created.board2");
            session.save(created);
            assertTrue(BoardFile.read(created).getSourceHeader().startsWith("# MegaMek Data (C) "
                  + java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) + " by The MegaMek Team"));
            Path legacy = directory.resolve("Own.board");
            Files.writeString(legacy, "# My own map\r\nsize 2 2\r\nhex 0101 0 \"\" \"\"\r\nhex 0201 0 \"\" \"\"\r\n"
                  + "hex 0102 0 \"\" \"\"\r\nhex 0202 0 \"\" \"\"\r\nend\r\n");
            session.open(legacy);
            Path imported = directory.resolve("Own.board2");
            session.save(imported);
            assertEquals("# My own map\r\n", BoardFile.read(imported).getSourceHeader());
        } finally {
            preferences.setValue(megamek.client.ui.clientGUI.GUIPreferences.BOARD_SAVE_INCLUDE_LICENSE, include);
        }
    }

    /**
     * The Deployment zone card paints a real zone (zone 1 by default): Paint adds its zone to every footprint hex and
     * keeps their other zones, Erase removes only that zone, and the terrain goes with the last one. One undo step per
     * stroke, and the game reads the zones (Board#getCustomDeploymentZone) from the saved file.
     */
    @Test void deploymentZoneCardAddsAndErasesOnlyItsZone() throws Exception {
        var session = session();
        Coords centre = new Coords(5, 5);
        send(session, Action.CHOOSE_BRUSH, "scenario", "deployment");
        assertEquals(List.of(1), Board.exitsAsIntList(session.snapshot().activeBrush().property("deployment_zone").exits()));
        send(session, Action.BRUSH, "", "2");
        send(session, Action.BRUSH_VALUE, "zone", "3");
        session.pointer(centre, 0, 0, false); session.finishStroke();
        var footprint = Set.copyOf(session.brush(centre));
        assertEquals(7, footprint.size());
        Path file = directory.resolve("zones.board2");
        session.save(file);
        assertEquals(footprint, BoardFile.read(file).getCustomDeploymentZone(3));
        send(session, Action.BRUSH, "", "1");
        send(session, Action.BRUSH_VALUE, "zone", "5");
        session.pointer(centre, 0, 0, false); session.finishStroke();
        assertEquals(List.of(3, 5), zones(session, centre));
        assertEquals(List.of(3), zones(session, centre.translated(0)), "A neighbour keeps its own zone");
        send(session, Action.TOOL, "", "ERASE");
        send(session, Action.BRUSH_VALUE, "zone", "3");
        session.pointer(centre, 0, 0, false); session.finishStroke();
        assertEquals(List.of(5), zones(session, centre));
        send(session, Action.BRUSH_VALUE, "zone", "5");
        session.pointer(centre, 0, 0, false); session.finishStroke();
        assertFalse(session.board().getHex(centre).containsTerrain(Terrains.DEPLOYMENT_ZONE), "The last zone takes the terrain");
        assertEquals(List.of(3), zones(session, centre.translated(0)));
        for (int step = 0; step < 3; step++) { send(session, Action.UNDO, "", ""); }
        assertEquals(List.of(3), zones(session, centre), "Each stroke is one undo step");
        send(session, Action.UNDO, "", "");
        assertTrue(footprint.stream().noneMatch(hex -> session.board().getHex(hex).containsTerrain(Terrains.DEPLOYMENT_ZONE)));
    }

    /** Edit's Zone IDs: 1 to 31 as the game reads them; out-of-range IDs are refused and an empty list removes the zone. */
    @Test void zoneIdsOfTheEditPanelRoundTripAndRefuseOtherIds() throws Exception {
        var session = session();
        send(session, Action.TERRAIN, "deployment_zone", "1");
        Terrain added = session.board().getHex(at).getTerrain(Terrains.DEPLOYMENT_ZONE);
        assertEquals(List.of(1), Board.exitsAsIntList(added.getExits()), "Add property gives zone 1");
        assertTrue(added.hasExitsSpecified(), "Specified, so the board never derives the IDs from neighbours");
        assertEquals(0, added.getLevel());
        send(session, Action.ZONES, "", "1, 31");
        Path file = directory.resolve("zone-ids.board2");
        session.save(file);
        assertTrue(Files.readString(file).contains("\"deploymentZones\":[1,31]"));
        Board read = BoardFile.read(file);
        assertEquals(Set.of(at), read.getCustomDeploymentZone(1));
        assertEquals(Set.of(at), read.getCustomDeploymentZone(31));
        for (String refused : List.of("0", "32", "3, 40", "1, a")) {
            send(session, Action.ZONES, "", refused);
            assertEquals(Messages.getString("BoardEditor.deploymentZoneRange", 31), session.snapshot().message(), refused);
            assertEquals(List.of(1, 31), zones(session, at));
        }
        assertEquals("1, 31", BoardEditorSession.zoneIds(session.board().getHex(at).getTerrain(Terrains.DEPLOYMENT_ZONE).getExits()));
        send(session, Action.ZONES, "", "");
        assertFalse(session.board().getHex(at).containsTerrain(Terrains.DEPLOYMENT_ZONE));
    }

    /**
     * Resize grows or cuts each edge in one undo step: every hex keeps its terrain and its objects with their groups, the
     * board its settings, notes and Save's file, and the issues follow their hexes. A board setting changed after the
     * resize stays when the resize is undone or redone.
     */
    @Test void resizeIsOneUndoStepThatKeepsTheBoardsContentAndSettings() throws Exception {
        var session = session();
        var ground = BoardDecoration.Placement.ground();
        Coords other = new Coords(3, 2), broken = new Coords(6, 6);
        session.board().setHex(at, new Hex(2, "woods:1;foliage_elev:2", ""));
        session.board().getHex(at).setDecorations(List.of(
              new BoardDecoration("car", "prop", "scenery/vehicles/car", null, 0, 0, 0, false, 1, ground, 0).withGroup("g")));
        session.board().getHex(other).setDecorations(List.of(
              new BoardDecoration("van", "prop", "scenery/vehicles/car", null, .2, 0, 0, false, 1, ground, 0).withGroup("g")));
        session.board().setHex(broken, new Hex(0, "industrial_elevator:3:266", ""));
        session.board().setDescription("Ridge");
        session.board().setAnnotations(other, List.of("Note"));
        send(session, Action.BOARD, "arena", "true");
        session.validateAll();
        Path file = directory.resolve("resize.board2");
        session.save(file);
        var hex = BoardFile.hexNode(session.board().getHex(at));
        var van = BoardFile.hexNode(session.board().getHex(other));

        send(session, Action.RESIZE, "", "2,1,1,3");
        assertEquals(Messages.getString("BoardEditor.resized", 20, 20), session.snapshot().message());
        assertEquals(20, session.board().getWidth());
        assertEquals(20, session.board().getHeight());
        assertEquals(new Coords(4, 4), megamek.common.util.BoardUtilities.resized(at, 2, 3), "Odd column 1 also moves a row down");
        assertEquals(hex, BoardFile.hexNode(session.board().getHex(new Coords(4, 4))));
        assertEquals(van, BoardFile.hexNode(session.board().getHex(megamek.common.util.BoardUtilities.resized(other, 2, 3))));
        assertTrue(session.snapshot().arena());
        assertEquals("Ridge", session.board().getDescription());
        assertEquals(List.of("Note"), List.copyOf(session.board().getAnnotations(megamek.common.util.BoardUtilities.resized(other, 2, 3))));
        Coords movedIssue = megamek.common.util.BoardUtilities.resized(broken, 2, 3);
        assertTrue(session.snapshot().issues().stream().anyMatch(issue -> issue.coords().equals(movedIssue)), session.snapshot().issues().toString());
        assertEquals(file, session.path());
        assertTrue(session.dirty());

        send(session, Action.BOARD, "tag", "Later");
        send(session, Action.UNDO, "", "");
        assertEquals(16, session.board().getWidth());
        assertEquals(17, session.board().getHeight());
        assertEquals(hex, BoardFile.hexNode(session.board().getHex(at)));
        assertTrue(session.snapshot().issues().stream().anyMatch(issue -> issue.coords().equals(broken)));
        assertTrue(session.snapshot().tags().contains("Later"), "A board setting is no undo step");
        assertTrue(session.snapshot().arena());
        send(session, Action.REDO, "", "");
        assertEquals(20, session.board().getWidth());
        assertEquals(hex, BoardFile.hexNode(session.board().getHex(new Coords(4, 4))));
        assertTrue(session.snapshot().tags().contains("Later"));

        send(session, Action.RESIZE, "", "-1,0,0,0");
        assertEquals(19, session.board().getHeight());
        assertEquals(hex, BoardFile.hexNode(session.board().getHex(new Coords(4, 3))), "Cutting the north edge moves the rest up");
        send(session, Action.RESIZE, "", "0,-20,0,0");
        assertEquals(Messages.getString("BoardEditor.resizeInvalid"), session.snapshot().message());
        assertEquals(20, session.board().getWidth());
    }

    /** Random map: the generator's board becomes the document through New's path, of the chosen size and saved state. */
    @Test void aRandomBoardBecomesTheDocumentAsNewDoes() {
        var session = session();
        send(session, Action.ELEVATION, "", "3");
        var settings = MapSettings.getInstance();
        settings.setBoardSize(12, 14);
        session.newRandom(settings);
        assertEquals(12, session.board().getWidth());
        assertEquals(14, session.board().getHeight());
        assertNull(session.path());
        assertFalse(session.dirty());
        assertFalse(session.snapshot().canUndo());
        assertTrue(session.board().isNativeFormat(), "Converted on the way in, as every editor document");
        assertEquals(Messages.getString("BoardEditor.randomBoardDone", 12, 14), session.snapshot().message());
    }

    /**
     * Decision P: a board with a rules error saves, the editor reopens it with the error in its issues list, and every
     * other reader refuses it, as it refuses the same error in a legacy board; the lobby's thumbnails, which continue on
     * errors for a legacy board, list the errors of a .board2 the same way.
     */
    @Test void aBoardWithRulesErrorsSavesReopensInTheEditorAndGamesRefuseIt() throws Exception {
        var session = session();
        Coords broken = new Coords(5, 5);
        session.board().setHex(broken, new Hex(0, "industrial_elevator:3:266", ""));
        session.validateAll();
        Path file = directory.resolve("broken.board2");
        session.save(file);
        assertFalse(session.dirty());
        var reopened = new BoardEditorSession();
        reopened.open(file);
        assertEquals(file, reopened.path());
        assertTrue(reopened.snapshot().issues().stream().anyMatch(issue -> issue.coords().equals(broken) && issue.object().isEmpty()
              && issue.text().contains("shaft bottom (3) is above the shaft top (1)")), reopened.snapshot().issues().toString());
        assertTrue(reopened.snapshot().message().endsWith(Messages.getString("BoardEditor.rulesErrors", 1)), reopened.snapshot().message());
        Path legacy = directory.resolve("broken.board");
        Files.writeString(legacy, "size 1 1\r\nhex 0101 0 \"industrial_elevator:3:266\" \"\"\r\nend\r\n");
        for (Path refused : List.of(file, legacy)) {
            assertThrows(java.io.IOException.class, () -> BoardFile.read(refused));
            assertThrows(IllegalArgumentException.class, () -> BoardFile.loadSheet(refused.toFile(), false, true),
                  "A game, the lobby's preview and the map preview load sheets this way");
            List<String> errors = new java.util.ArrayList<>();
            Board thumbnail = new Board();
            try (var stream = Files.newInputStream(refused)) { thumbnail.load(stream, errors, true); }
            assertTrue(thumbnail.getWidth() > 0 && !errors.isEmpty(), refused + ": " + errors);
        }
        // Random-map selection skips such a board (its strict read refuses it) instead of failing the lobby or the game.
        var boards = megamek.common.Configuration.boardsDir();
        try {
            megamek.common.Configuration.setBoardsDir(directory.toFile());
            MapSettings settings = MapSettings.getInstance();
            settings.setBoardsAvailableVector(List.of(MapSettings.BOARD_GENERATED, MapSettings.BOARD_RANDOM,
                  MapSettings.BOARD_SURPRISE, file.getFileName().toString()));
            settings.setBoardsSelectedVector(List.of(MapSettings.BOARD_RANDOM));
            settings.replaceBoardWithRandom(MapSettings.BOARD_RANDOM);
            assertTrue(settings.getBoardsSelectedVector().getFirst().endsWith(MapSettings.BOARD_GENERATED),
                  settings.getBoardsSelectedVector().toString());
            assertFalse(settings.getBoardsAvailableVector().contains(file.getFileName().toString()));
        } finally {
            megamek.common.Configuration.setBoardsDir(boards);
        }
    }

    /** Save As Image writes the old editor's printable board: one 84 × 72 tile of 2D tileset art per hex. */
    @Test void saveAsImageWritesThePrintableBoard() throws Exception {
        var session = session();
        send(session, Action.NEW, "", "5x4");
        session.board().setHex(new Coords(2, 1), new Hex(0, "woods:2", ""));
        Path file = directory.resolve("board.png");
        session.saveImage(file);
        var image = javax.imageio.ImageIO.read(file.toFile());
        assertEquals(5 * 84 * 3 / 4 + 84 / 4, image.getWidth());
        assertEquals(4 * 72 + 72 / 2, image.getHeight());
        // The centres of hex 0101 (clear) and of hex 0302 (heavy woods; an even column starts at its row's top).
        assertNotEquals(image.getRGB(42, 36), image.getRGB(2 * 63 + 42, 72 + 36), "The tileset art is drawn");
        assertTrue(session.snapshot().message().contains("board.png"), session.snapshot().message());
    }

    /**
     * The saved image prints every hex's coordinates and type on an outline even with all three view options off:
     * hex 0302, depth-0 water, has DEPTH 0 at its bottom edge and its number at its top.
     */
    @Test void saveAsImagePrintsCoordinatesAndHexTypesBlackOnAWhiteOutline() throws Exception {
        var preferences = megamek.client.ui.clientGUI.GUIPreferences.getInstance();
        boolean coords = preferences.getCoordsEnabled(), types = preferences.getHexTypesEnabled();
        boolean outline = preferences.getHexLabelOutline();
        try {
            if (coords) { preferences.toggleCoords(); }
            if (types) { preferences.toggleHexTypes(); }
            preferences.setHexLabelOutline(false);
            var session = session();
            send(session, Action.NEW, "", "5x4");
            session.board().setHex(new Coords(2, 1), new Hex(0, "water:0", ""));
            Path file = directory.resolve("types.png");
            session.saveImage(file);
            var image = javax.imageio.ImageIO.read(file.toFile());
            assertFalse(preferences.getCoordsEnabled());
            assertFalse(preferences.getHexTypesEnabled());
            assertFalse(preferences.getHexLabelOutline());
            preferences.toggleCoords();
            preferences.toggleHexTypes();
            preferences.setHexLabelOutline(true);
            Path withOptionsFile = directory.resolve("types-options-on.png");
            session.saveImage(withOptionsFile);
            var withOptions = javax.imageio.ImageIO.read(withOptionsFile.toFile());
            assertArrayEquals(image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()),
                  withOptions.getRGB(0, 0, withOptions.getWidth(), withOptions.getHeight(), null, 0, withOptions.getWidth()),
                  "Print always includes coordinates, hex types and outlines, independently of the view options");
            // The tile of hex 0302 starts at (126, 72); its bottom line and its top line, each about 13 pixels high.
            for (int top : new int[] { 72 + 56, 72 + 5 }) {
                int black = 0, white = 0;
                for (int y = top; y < top + 13; y++) {
                    for (int x = 126 + 14; x < 126 + 70; x++) {
                        int rgb = image.getRGB(x, y), r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
                        if (Math.max(r, Math.max(g, b)) < 50) { black++; }
                        if (Math.min(r, Math.min(g, b)) > 225) { white++; }
                    }
                }
                // At 10 points the outline is subpixel: rasterization determines its near-white pixel count.
                // Check its presence here; BoardHexTextTest checks outline colour on a larger controlled glyph.
                assertTrue(black > 8 && white > 0, "Black text on white at row " + top + ": " + black + "/" + white);
            }
        } finally {
            if (preferences.getCoordsEnabled() != coords) { preferences.toggleCoords(); }
            if (preferences.getHexTypesEnabled() != types) { preferences.toggleHexTypes(); }
            preferences.setHexLabelOutline(outline);
        }
    }

    /**
     * Only overwrite clear hexes (the old editor's brush option): Paint and Sculpt leave every hex that holds terrain,
     * the brush preview shows only the hexes they change, and Erase is not filtered.
     */
    @Test void onlyOverwriteClearHexesLeavesHexesWithTerrainToPaintAndSculpt() {
        var session = session();
        Coords centre = new Coords(5, 5), building = centre.translated(0), water = centre.translated(3);
        session.board().setHex(building, new Hex(0, "building:2;bldg_cf:40;bldg_elev:2", ""));
        session.board().setHex(water, new Hex(0, "water:1", ""));
        send(session, Action.CHOOSE_BRUSH, "vegetation", "woods-1");
        send(session, Action.BRUSH, "", "2");
        assertEquals(7, session.brush(centre).size());
        send(session, Action.BRUSH_VALUE, "clearOnly", "true");
        assertTrue(session.snapshot().activeBrush().clearOnly());
        assertEquals(5, session.brush(centre).size(), "The preview leaves out the building and the water");
        session.pointer(centre, 0, 0, false); session.finishStroke();
        assertTrue(session.board().getHex(centre).containsTerrain(Terrains.WOODS));
        assertTrue(session.board().getHex(centre.translated(1)).containsTerrain(Terrains.WOODS));
        assertFalse(session.board().getHex(building).containsTerrain(Terrains.WOODS));
        assertFalse(session.board().getHex(water).containsTerrain(Terrains.WOODS));

        // Erase is not filtered: the woods hexes hold terrain, and the brush takes their woods.
        send(session, Action.TOOL, "", "ERASE");
        assertEquals(7, session.brush(centre).size());
        session.pointer(centre, 0, 0, false); session.finishStroke();
        assertFalse(session.board().getHex(centre).containsTerrain(Terrains.WOODS));

        // Sculpt with Slope steps the clear neighbours of a raised hex, but not the building beside it.
        Coords hill = new Coords(2, 2), house = hill.translated(1);
        session.board().setHex(house, new Hex(0, "building:2;bldg_cf:40;bldg_elev:2", ""));
        send(session, Action.TOOL, "", "SCULPT");
        send(session, Action.BRUSH, "", "1");
        for (int stroke = 0; stroke < 2; stroke++) { session.pointer(hill, 0, 0, false); session.finishStroke(); }
        assertEquals(2, session.board().getHex(hill).getLevel());
        assertEquals(1, session.board().getHex(hill.translated(4)).getLevel(), "Slope steps a clear neighbour");
        assertEquals(0, session.board().getHex(house).getLevel(), "and leaves the building's level");
        assertEquals(List.of(), session.brush(house));
        send(session, Action.BRUSH_VALUE, "clearOnly", "false");
        assertEquals(List.of(house), session.brush(house), "Switched off, the building's hex is in the footprint again");
    }

    /** Fill area: a Paint click covers the clicked hex's connected area of the same level and terrain, as one undo step. */
    @Test void fillPaintsTheConnectedAreaOnly() {
        var session = session();
        Coords centre = new Coords(5, 5), water = centre.translated(2), outside = centre.translated(0).translated(0);
        session.board().setHex(centre, new Hex(2));
        for (int direction = 0; direction < 6; direction++) { session.board().setHex(centre.translated(direction), new Hex(2)); }
        session.board().setHex(water, new Hex(2, "water:1", ""));
        send(session, Action.CHOOSE_BRUSH, "vegetation", "woods-1");
        send(session, Action.BRUSH_VALUE, "fill", "true");
        assertEquals(6, session.brush(centre).size(), "The level 2 hexes without the water");
        session.pointer(centre, 0, 0, false); session.finishStroke();
        assertTrue(session.board().getHex(centre.translated(5)).containsTerrain(Terrains.WOODS));
        assertFalse(session.board().getHex(water).containsTerrain(Terrains.WOODS));
        assertFalse(session.board().getHex(outside).containsTerrain(Terrains.WOODS), "Level 0 is another area");
        send(session, Action.UNDO, "", "");
        assertFalse(session.board().getHex(centre).containsTerrain(Terrains.WOODS));
        assertFalse(session.board().getHex(centre.translated(5)).containsTerrain(Terrains.WOODS));
    }

    /**
     * Copy also puts the hex as text on the clipboard, so another window's session pastes it with its level, theme,
     * terrain and objects (as new objects); the old editor's text format pastes too, and text that is no hex leaves
     * Paste with the session's own copy.
     */
    @Test void copiedHexesTravelAsTextBetweenWindowsAndTheOldTextFormatPastes() throws Exception {
        var clipboard = new java.awt.datatransfer.Clipboard("test");
        var from = session();
        var to = session();
        from.textClipboard(clipboard); to.textClipboard(clipboard);
        Hex hex = new Hex(2, "woods:1;foliage_elev:2", "snow");
        hex.setDecorations(List.of(prop("bench", .1, .2)));
        from.board().setHex(at, hex);
        send(from, Action.COPY, "", "");
        assertTrue(text(clipboard).startsWith("MegaMek Hex2"), text(clipboard));

        Coords target = new Coords(3, 3);
        to.pointer(target, 0, 0, false);
        send(to, Action.PASTE, "", "");
        Hex pasted = to.board().getHex(target);
        assertEquals(2, pasted.getLevel());
        assertEquals("snow", pasted.getTheme());
        assertEquals(1, pasted.terrainLevel(Terrains.WOODS));
        assertEquals(2, pasted.terrainLevel(Terrains.FOLIAGE_ELEV));
        assertEquals(1, pasted.getDecorations().size());
        assertEquals("scenery/vehicles/car", pasted.getDecorations().getFirst().asset());
        assertNotEquals("bench", pasted.getDecorations().getFirst().id(), "A pasted object is a new object");

        // The old editor's text, as a text editor holds it: a hex without a theme says "null".
        clipboard.setContents(new java.awt.datatransfer.StringSelection(
              "MegaMek Hex///Level###-1///Theme###null///Terrain###water:1"), null);
        send(to, Action.PASTE, "", "");
        pasted = to.board().getHex(target);
        assertEquals(-1, pasted.getLevel());
        assertEquals(1, pasted.terrainLevel(Terrains.WATER));
        assertNull(pasted.getTheme());
        assertTrue(pasted.getDecorations().isEmpty());

        // Other text: the copying session pastes its own copy; a session without one has nothing to paste.
        clipboard.setContents(new java.awt.datatransfer.StringSelection("not a hex"), null);
        Coords other = new Coords(4, 4);
        from.pointer(other, 0, 0, false);
        send(from, Action.PASTE, "", "");
        assertEquals("snow", from.board().getHex(other).getTheme());
        var fresh = session();
        fresh.textClipboard(clipboard);
        send(fresh, Action.PASTE, "", "");
        assertEquals("Copy a hex first", fresh.snapshot().message());
    }

    /**
     * A container yard card paints one look and one rotation on every hex of a stroke, its cover included (Scattered:
     * foliage); the Brush's and the Edit panel's Rotation each turn it in one undo step; switching between yards keeps the
     * rotation, another look drops it, and removing the vegetation drops the look.
     */
    @Test void aContainerYardStrokePaintsOneLookAndRotationAndItsRotationRowsTurnIt() {
        var session = session();
        String rows = "woods/container-yard/rows", crane = "woods/container-yard/quay-crane";
        send(session, Action.CHOOSE_BRUSH, "vegetation", rows);
        send(session, Action.BRUSH_VALUE, "rotation:vegetation", "60");
        send(session, Action.BRUSH, "", "2");
        session.pointer(at, 0, 0, false); session.finishStroke();
        var turned = new megamek.common.board.HexAppearance(rows, null, null, null, BoardDecoration.Colours.NONE, 60);
        List<Coords> painted = session.brush(at);
        assertEquals(7, painted.size());
        for (Coords hex : painted) {
            assertEquals(turned, session.board().getHex(hex).getAppearance().get("vegetation"), hex.toString());
            assertEquals(1, session.board().getHex(hex).terrainLevel(Terrains.WOODS));
            assertEquals(2, session.board().getHex(hex).terrainLevel(Terrains.FOLIAGE_ELEV));
        }
        send(session, Action.UNDO, "", "");
        assertTrue(painted.stream().allMatch(hex -> session.board().getHex(hex).getAppearance().isEmpty()), "One undo step");
        send(session, Action.REDO, "", "");
        // The Edit panel's Rotation (typed values snap to the 30-degree steps), one undo step.
        send(session, Action.TOOL, "", "SELECT"); session.pointer(at, 0, 0, false); session.finishStroke();
        send(session, Action.DESIGN_ROTATION, "vegetation", "100");
        assertEquals(90, session.board().getHex(at).getAppearance().get("vegetation").rotation());
        send(session, Action.UNDO, "", "");
        assertEquals(60, session.board().getHex(at).getAppearance().get("vegetation").rotation());
        // Another yard keeps the rotation; another look has none.
        send(session, Action.VARIANT, "vegetation", crane);
        assertEquals(new megamek.common.board.HexAppearance(crane, null, null, null, BoardDecoration.Colours.NONE, 60),
              session.board().getHex(at).getAppearance().get("vegetation"));
        send(session, Action.VARIANT, "vegetation", "woods/orchard");
        assertNull(session.board().getHex(at).getAppearance().get("vegetation").rotation());
        send(session, Action.DESIGN_ROTATION, "vegetation", "30");
        assertEquals(Messages.getString("BoardEditor.skinFirst"), session.snapshot().message());
        send(session, Action.VARIANT, "vegetation", crane);
        send(session, Action.REMOVE_COMPONENT, "", "vegetation");
        assertTrue(session.board().getHex(at).getAppearance().isEmpty(), "The look leaves with its woods");
        // The Scattered card paints the foliage cover its look shows.
        send(session, Action.CHOOSE_BRUSH, "vegetation", "woods/container-yard/scattered");
        send(session, Action.BRUSH, "", "1");
        session.pointer(at, 0, 0, false); session.finishStroke();
        assertEquals(1, session.board().getHex(at).terrainLevel(Terrains.FOLIAGE_ELEV));
        assertEquals("woods/container-yard/scattered", session.board().getHex(at).getAppearance().get("vegetation").variant());
    }

    /**
     * Pavement Edges: the Brush's Edges paints Fitted on every hex of a stroke and Auto clears it; the Edit panel's row
     * sets Hex in one undo step and is refused on a hex without pavement; a saved .board2 keeps Hex.
     */
    @Test void pavementEdgesArePaintedSetInTheEditRowAndSaved() throws Exception {
        var session = session();
        send(session, Action.EDGES, "", "fitted");
        assertEquals(Messages.getString("BoardEditor.pavementFirst"), session.snapshot().message());
        assertTrue(session.board().getHex(at).getAppearance().isEmpty());
        send(session, Action.CHOOSE_BRUSH, "pavement", "1");
        send(session, Action.BRUSH_VALUE, "edges", "fitted");
        send(session, Action.BRUSH, "", "2");
        session.pointer(at, 0, 0, false); session.finishStroke();
        List<Coords> painted = session.brush(at);
        assertEquals(7, painted.size());
        for (Coords hex : painted) {
            assertEquals(megamek.common.board.HexAppearance.EDGES_FITTED, session.board().getHex(hex).getAppearance().get("pavement"));
            assertTrue(session.board().getHex(hex).containsTerrain(Terrains.PAVEMENT));
        }
        send(session, Action.BRUSH_VALUE, "edges", "auto");
        session.pointer(at, 0, 0, false); session.finishStroke();
        assertTrue(painted.stream().allMatch(hex -> session.board().getHex(hex).getAppearance().isEmpty()), "Auto is stored as none");
        send(session, Action.TOOL, "", "SELECT"); session.pointer(at, 0, 0, false); session.finishStroke();
        send(session, Action.EDGES, "", "hex");
        assertEquals(megamek.common.board.HexAppearance.Edges.HEX,
              megamek.common.board.HexAppearance.edges(session.board().getHex(at).getAppearance()));
        send(session, Action.UNDO, "", "");
        assertTrue(session.board().getHex(at).getAppearance().isEmpty(), "One undo step");
        send(session, Action.REDO, "", "");
        Path file = directory.resolve("edges.board2"); session.save(file);
        assertEquals(megamek.common.board.HexAppearance.EDGES_HEX, BoardFile.read(file).getHex(at).getAppearance().get("pavement"));
    }

    /**
     * Fortification: the Edit row sets Sandbags on a fortified hex and a saved .board2 keeps it; Dug in is stored as none,
     * and refused where a road runs, which always takes sandbags.
     */
    @Test void fortificationIsChosenInEditSavedAndSandbagsOnlyOnRoads() throws Exception {
        var session = session();
        send(session, Action.FORTIFICATION, "", "sandbags");
        assertEquals(Messages.getString("BoardEditor.addTerrainFirst"), session.snapshot().message());
        send(session, Action.TERRAIN, "fortified", "1");
        send(session, Action.FORTIFICATION, "", "sandbags");
        assertEquals(megamek.common.board.HexAppearance.SANDBAGGED, session.board().getHex(at).getAppearance().get("fortification"));
        Path file = directory.resolve("fortified.board2"); session.save(file);
        assertEquals(megamek.common.board.HexAppearance.SANDBAGGED,
              BoardFile.read(file).getHex(at).getAppearance().get("fortification"));
        send(session, Action.FORTIFICATION, "", "dug");
        assertTrue(session.board().getHex(at).getAppearance().isEmpty(), "Dug in is stored as none");
        send(session, Action.TERRAIN, "road", "1");
        send(session, Action.FORTIFICATION, "", "dug");
        assertEquals(Messages.getString("BoardEditor.fortification.sandbagsOnly"), session.snapshot().message());
        Hex road = session.board().getHex(at);
        assertEquals(megamek.common.board.HexAppearance.Fortification.SANDBAGS,
              megamek.common.board.HexAppearance.fortification(road.getAppearance(), road::containsTerrain));
    }

    /**
     * Scorched ground: a member of its card paints, the Brush's Severity switches the level, Erase takes it off, the Edit
     * row sets it in one undo step and a saved .board2 keeps it; it is a look, so the ground stays CLEAR.
     */
    @Test void scorchedGroundIsPaintedErasedSetInEditAndSaved() throws Exception {
        var session = session();
        send(session, Action.CHOOSE_BRUSH, "scorch", "scorch/charred");
        send(session, Action.BRUSH_VALUE, "variant:scorch", "scorch/ash");
        send(session, Action.BRUSH, "", "2");
        session.pointer(at, 0, 0, false); session.finishStroke();
        List<Coords> painted = session.brush(at);
        assertEquals(7, painted.size());
        for (Coords hex : painted) {
            assertEquals(.62f, megamek.common.board.HexAppearance.scorch(session.board().getHex(hex).getAppearance()));
            assertTrue(session.board().getHex(hex).isClear());
        }
        send(session, Action.TOOL, "", "ERASE");
        session.pointer(at, 0, 0, false); session.finishStroke();
        assertTrue(painted.stream().allMatch(hex -> session.board().getHex(hex).getAppearance().isEmpty()));
        send(session, Action.TOOL, "", "SELECT"); session.pointer(at, 0, 0, false); session.finishStroke();
        send(session, Action.VARIANT, "scorch", "scorch/glass");
        assertEquals(1f, megamek.common.board.HexAppearance.scorch(session.board().getHex(at).getAppearance()));
        send(session, Action.UNDO, "", "");
        assertTrue(session.board().getHex(at).getAppearance().isEmpty(), "One undo step");
        send(session, Action.REDO, "", "");
        Path file = directory.resolve("scorch.board2"); session.save(file);
        assertEquals("scorch/glass", BoardFile.read(file).getHex(at).getAppearance().get("scorch").variant());
    }

    private static String text(java.awt.datatransfer.Clipboard clipboard) throws Exception {
        return (String) clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor);
    }

    private static List<Integer> zones(BoardEditorSession session, Coords hex) {
        Terrain zone = session.board().getHex(hex).getTerrain(Terrains.DEPLOYMENT_ZONE);
        return zone == null ? List.of() : Board.exitsAsIntList(zone.getExits());
    }

    private static Map<Coords, Object> nodes(BoardEditorSession session, Coords... hexes) {
        Map<Coords, Object> nodes = new java.util.LinkedHashMap<>();
        for (Coords hex : hexes) { nodes.put(hex, BoardFile.hexNode(session.board().getHex(hex))); }
        return nodes;
    }
}
