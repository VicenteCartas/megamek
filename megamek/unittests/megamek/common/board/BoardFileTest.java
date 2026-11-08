/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import megamek.common.Hex;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoardFileTest {
    @Test void calculatedStructureExitsSurviveNativeAndClipboardSerialization() throws Exception {
        for (int type : new int[] { Terrains.BUILDING, Terrains.FUEL_TANK }) {
            Board board = Board.createEmptyBoard(1, 2);
            for (int y = 0; y < 2; y++) {
                Hex hex = new Hex(0, type == Terrains.BUILDING
                      ? "bldg_cf:15;bldg_elev:2;bldg_basement_type:1"
                      : "fuel_tank_cf:15;fuel_tank_elev:2;fuel_tank_magn:100", "");
                hex.addTerrain(new Terrain(type, 1));
                board.setHex(0, y, hex);
            }
            ByteArrayOutputStream legacyText = new ByteArrayOutputStream();
            board.saveLegacy(legacyText, false);
            Board legacy = new Board();
            legacy.load(legacyText.toString(StandardCharsets.UTF_8), null);
            Board nativeBoard = BoardFile.readNative(encode(board));
            for (int y = 0; y < 2; y++) {
                Hex source = board.getHex(0, y);
                int expected = y == 0 ? 8 : 1;
                assertEquals(expected, source.getTerrain(type).getExits());
                assertFalse(source.getTerrain(type).hasExitsSpecified(), "Saving must not mutate the live terrain");
                assertEquals(expected, legacy.getHex(0, y).getTerrain(type).getExits());
                assertEquals(expected, nativeBoard.getHex(0, y).getTerrain(type).getExits());
                assertEquals(expected, BoardFile.fromClipboard(BoardFile.clipboard(source)).getTerrain(type).getExits());
            }
            assertSame(nativeBoard.getBuildingAt(new Coords(0, 0)), nativeBoard.getBuildingAt(new Coords(0, 1)),
                  "The game must reconstruct one connected structure");
        }
    }

    @Test void terrainPreviewsReadBothFormatsWithoutResolvingVisualAssets() throws Exception {
        String nativeBoard = """
              {"format":"megamek-board","version":3,"size":{"width":1,"height":1},"hexes":[
                {"at":[1,1],"elevation":2,"theme":"mars",
                 "components":{"water":{"depth":2},"road":{"type":"paved","connections":["n","s"]},
                   "building":{"appearance":{"asset":"buildings/missing","uid":"missing-building-id"}}},
                 "decorations":[{"id":"object","kind":"prop","asset":"missing/object","uid":"missing-object-id"}]}
              ]}
              """;
        String legacyBoard = "size 1 1\nhex 0101 2 \"water:2;road:1:9\" \"mars\"\nend\n";
        try (var models = mockStatic(BoardModelIds.class)) {
            for (String text : List.of(nativeBoard, legacyBoard)) {
                Board preview = BoardFile.readTerrain(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
                Hex hex = preview.getHex(0, 0);
                assertEquals(1, preview.getWidth());
                assertEquals(1, preview.getHeight());
                assertEquals(2, hex.getLevel());
                assertEquals(2, hex.terrainLevel(Terrains.WATER));
                assertEquals(9, hex.getTerrain(Terrains.ROAD).getExits());
                assertEquals("mars", hex.getTheme());
                assertTrue(hex.getAppearance().isEmpty());
                assertTrue(hex.getDecorations().isEmpty());
            }
            models.verifyNoInteractions();
        }
    }

    @Test void legacyRoadSelectorsNormalizeBeforeEitherFormatIsResaved() throws Exception {
        for (int selector : new int[] { -42, 0, 1, 2, 3, 4, 5, 6, 7, 34, 90, Integer.MAX_VALUE }) {
            int expected = selector == 0 ? Terrain.LEVEL_NONE : selector >= 1 && selector <= 4 ? selector : 1;
            String terrain = "road:" + selector + ":44;rough:2";
            Board loaded = new Board();
            loaded.load("size 1 1\noption exit_roads_to_pavement false\nhex 0101 0 \"" + terrain + "\" \"grass\"\nend\n", null);
            Hex hex = loaded.getHex(0, 0);
            assertEquals(expected, hex.terrainLevel(Terrains.ROAD), "Selector " + selector);
            if (selector == 0) {
                assertFalse(hex.containsTerrain(Terrains.ROAD));
            } else {
                assertEquals(44, hex.getTerrain(Terrains.ROAD).getExits());
                assertTrue(hex.getTerrain(Terrains.ROAD).hasExitsSpecified());
            }
            assertEquals(2, hex.terrainLevel(Terrains.ROUGH));
            assertEquals("grass", hex.getTheme());
            String saved = encode(loaded);
            assertEquals(selector != 0, new com.fasterxml.jackson.databind.ObjectMapper().readTree(saved)
                  .path("hexes").get(0).path("components").has("road"), saved);
            assertEquals(expected, BoardFile.readNative(saved).getHex(0, 0).terrainLevel(Terrains.ROAD));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            loaded.saveLegacy(output, false);
            String oldFormat = output.toString(StandardCharsets.UTF_8);
            assertEquals(selector != 0, oldFormat.contains("road:"), oldFormat);
            if (selector != 0) {
                assertEquals(44, BoardFile.readNative(saved).getHex(0, 0).getTerrain(Terrains.ROAD).getExits());
                assertTrue(oldFormat.contains("road:" + expected + ":44"), oldFormat);
            }
            Board reread = new Board(); reread.load(oldFormat, null);
            assertEquals(expected, reread.getHex(0, 0).terrainLevel(Terrains.ROAD));
        }
    }

    @Test void clipboardRoadNormalizationPreservesImplicitExitsAndOtherTerrainAndLeavesRuleParsingAlone() throws Exception {
        Hex source = new Hex(0, "road:90;bridge_road:7:9;rough:2", "grass");
        assertEquals(90, source.terrainLevel(Terrains.ROAD), "Generic hex parsing also parses tileset rules");
        assertEquals(Terrain.WILDCARD, new Hex(0, "road:*", "").terrainLevel(Terrains.ROAD));
        Hex loaded = Hex.parseClipboardString(source.getClipboardString());
        assertNotNull(loaded);
        assertEquals(1, loaded.terrainLevel(Terrains.ROAD));
        assertFalse(loaded.getTerrain(Terrains.ROAD).hasExitsSpecified());
        assertEquals(0, loaded.getTerrain(Terrains.ROAD).getExits());
        assertEquals(source.getTerrain(Terrains.BRIDGE_ROAD).toString(), loaded.getTerrain(Terrains.BRIDGE_ROAD).toString());
        assertEquals(source.getTerrain(Terrains.ROUGH).toString(), loaded.getTerrain(Terrains.ROUGH).toString());
        assertEquals(source.getTheme(), loaded.getTheme());
        Hex zero = Hex.parseClipboardString(new Hex(0, "road:0:44;fluff:5:4", "").getClipboardString());
        assertNotNull(zero);
        assertFalse(zero.containsTerrain(Terrains.ROAD));
        assertEquals("fluff:5:4", zero.getTerrain(Terrains.FLUFF).toString());
        for (int selector : new int[] { 0, 5, 90 }) {
            Hex nativeSource = new Hex(0, "road:" + selector + ":44", "");
            assertEquals(selector, BoardFile.fromClipboard(BoardFile.clipboard(nativeSource)).terrainLevel(Terrains.ROAD));
            String nativeText = "{\"format\":\"megamek-board\",\"version\":3,\"size\":{\"width\":1,\"height\":1},"
                  + "\"hexes\":[{\"at\":[1,1],\"elevation\":0,\"components\":{\"road\":{\"type\":" + selector
                  + ",\"connections\":[\"se\",\"s\",\"nw\"]}}}]}";
            assertEquals(selector, BoardFile.readNative(nativeText, true).getHex(0, 0).terrainLevel(Terrains.ROAD),
                  "Native documents retain their stored values");
        }
    }

    @Test void genevaParkingCarsSurviveRoadNormalizationAndNativeConversion() throws Exception {
        // Exact terrain from GenevaT49 hexes 1404 and 1202; their cars are selected by fluff, independently of road.
        for (String terrain : List.of("road:5:44;fluff:5:4", "road:6:9;fluff:5:2")) {
            Hex original = new Hex(0, terrain, "grass");
            var models = TilesetScenery.resolve(original, false).scenery().models();
            String expected = terrain.endsWith(":4") ? "scenery/fluff/cars_5" : "scenery/fluff/cars_3";
            assertEquals(List.of(expected), models);
            Board loaded = new Board();
            loaded.load("size 1 1\nhex 0101 0 \"" + terrain + "\" \"grass\"\nend\n", null);
            Hex hex = loaded.getHex(0, 0);
            assertEquals(1, hex.terrainLevel(Terrains.ROAD));
            assertEquals(original.getTerrain(Terrains.ROAD).getExits(), hex.getTerrain(Terrains.ROAD).getExits());
            assertEquals(original.getTerrain(Terrains.FLUFF).toString(), hex.getTerrain(Terrains.FLUFF).toString());
            assertEquals(models, TilesetScenery.resolve(hex, false).scenery().models());
            ByteArrayOutputStream output = new ByteArrayOutputStream(); loaded.saveLegacy(output, false);
            Board legacyAgain = new Board(); legacyAgain.load(output.toString(StandardCharsets.UTF_8), null);
            assertEquals(models, TilesetScenery.resolve(legacyAgain.getHex(0, 0), false).scenery().models());
            BoardSceneryLayouts.convert(loaded);
            var decorations = loaded.getHex(0, 0).getDecorations();
            assertTrue(decorations.stream().anyMatch(object -> object.asset().startsWith("scenery/vehicles/")));
            Board nativeAgain = BoardFile.readNative(encode(loaded));
            assertEquals(decorations, nativeAgain.getHex(0, 0).getDecorations());
            assertEquals(1, nativeAgain.getHex(0, 0).terrainLevel(Terrains.ROAD));
            assertEquals(original.getTerrain(Terrains.ROAD).getExits(), nativeAgain.getHex(0, 0).getTerrain(Terrains.ROAD).getExits());
        }
    }

    @Test void roadTrafficControlsRoundTripWithoutReplacingSurfacesOrRailLayouts() throws Exception {
        Board board = Board.createEmptyBoard(1, 1);
        Hex hex = new Hex(0, "road:1:27;rail:1:9;bridge:1:27;bridge_cf:40;bridge_elev:1;bridge_road:1:27", "");
        var road = HexAppearance.withTrafficControl(new HexAppearance("road/rounded", null, "asphalt", null),
              HexAppearance.TrafficControl.SIGNALS);
        var lights = HexAppearance.withTrafficControl(null, HexAppearance.TrafficControl.SIGNALS);
        hex.setAppearance(Map.of("road", road, "bridgeRoad", lights, "rail", HexAppearance.rail(3, 64)));
        board.setHex(0, 0, hex);
        String saved = encode(board);
        assertTrue(saved.contains("\"trafficControl\":\"signals\""), saved);
        assertEquals(hex.getAppearance(), BoardFile.readNative(saved).getHex(0, 0).getAppearance());
        assertEquals(hex.getAppearance(), BoardFile.fromClipboard(BoardFile.clipboard(hex)).getAppearance());
        assertThrows(java.io.IOException.class, () -> BoardFile.readNative(saved.replace("\"signals\"", "\"unknown\"")));
        assertThrows(IllegalArgumentException.class, () -> lights.validateOwner("rail"));
        assertEquals(new HexAppearance("road/rounded", null, "asphalt", null),
              HexAppearance.withTrafficControl(road, null));
        assertNull(HexAppearance.withTrafficControl(lights, null));
        var gantry = HexAppearance.withTrafficControl(road, HexAppearance.TrafficControl.GANTRY);
        var stops = HexAppearance.withTrafficControl(null, HexAppearance.TrafficControl.STOPS);
        hex.setAppearance(Map.of("road", gantry, "bridgeRoad", stops, "rail", HexAppearance.rail(3, 64)));
        String overhead = encode(board);
        assertTrue(overhead.contains("\"trafficControl\":\"gantry\""), "An explicit gantry choice survives a later topology change");
        assertTrue(overhead.contains("\"trafficControl\":\"stops\""), "An explicit stop-only choice survives saving");
        assertEquals(hex.getAppearance(), BoardFile.readNative(overhead).getHex(0, 0).getAppearance());
        assertEquals(hex.getAppearance(), BoardFile.fromClipboard(BoardFile.clipboard(hex)).getAppearance());
        String explicitGantry = saved.replace("\"signals\"", "\"gantry\"");
        assertEquals(Map.of("road", gantry, "bridgeRoad", HexAppearance.withTrafficControl(null, HexAppearance.TrafficControl.GANTRY),
                    "rail", HexAppearance.rail(3, 64)),
              BoardFile.readNative(explicitGantry).getHex(0, 0).getAppearance(),
              "Both material-bearing and control-only gantries retain their explicit choice");
        assertThrows(java.io.IOException.class, () -> BoardFile.readNative(explicitGantry.replace("\"bridgeRoad\"", "\"ground\"")));
        assertThrows(java.io.IOException.class, () -> BoardFile.readNative(explicitGantry.replace("\"road\"", "\"pavement\"")));
        hex.setAppearance(Map.of("road", new HexAppearance(null, null, "asphalt", null)));
        assertFalse(encode(board).contains("trafficControl"), "The default adds no field to existing appearances");
        assertEquals(HexAppearance.TrafficControl.GANTRY,
              HexAppearance.trafficControl(BoardFile.readNative(encode(board)).getHex(0, 0).getAppearance(), "road"));
    }

    @Test void tJunctionDefaultsAndExplicitGantryOverridesSurviveSavingAndClipboard() throws Exception {
        Board board = Board.createEmptyBoard(1, 1);
        Hex hex = new Hex(0, "road:1:11;bridge:1:11;bridge_cf:40;bridge_elev:1;bridge_road:1:11", "");
        board.setHex(0, 0, hex);
        assertEquals(HexAppearance.TrafficControl.STOPS, HexAppearance.trafficControl(hex.getAppearance(), "road", 11));
        assertEquals(HexAppearance.TrafficControl.GANTRY, HexAppearance.trafficControl(hex.getAppearance(), "road", 27));
        var surface = new HexAppearance("road/rounded", null, "asphalt", null);
        hex.setAppearance(Map.of("road", HexAppearance.withTrafficControl(surface, HexAppearance.TrafficControl.GANTRY),
              "bridgeRoad", HexAppearance.withTrafficControl(null, HexAppearance.TrafficControl.GANTRY)));
        for (Hex loaded : List.of(BoardFile.readNative(encode(board)).getHex(0, 0),
              BoardFile.fromClipboard(BoardFile.clipboard(hex)))) {
            assertEquals(hex.getAppearance(), loaded.getAppearance());
            for (String owner : List.of("road", "bridgeRoad")) {
                assertEquals(HexAppearance.TrafficControl.GANTRY, HexAppearance.trafficControl(loaded.getAppearance(), owner, 11),
                      "Explicit signals must not revert to priority stops at a T");
            }
        }
        hex.setAppearance(Map.of("road", surface));
        Hex loaded = BoardFile.readNative(encode(board)).getHex(0, 0);
        assertEquals(HexAppearance.TrafficControl.STOPS, HexAppearance.trafficControl(loaded.getAppearance(), "road", 11),
              "A surface with no stored control keeps the topology-dependent default");
    }

    @Test void threeAxisRotationRoundTripsAndOldObjectsDefaultToUpright() throws Exception {
        Board board = Board.createEmptyBoard(1, 1);
        var tilted = new BoardDecoration("tilted", "prop", "scenery/vehicles/car", null, .1, .2, 75,
              true, 1.2, BoardDecoration.Placement.ground(), 0, false, 30, -45);
        board.getHex(0, 0).setDecorations(List.of(tilted));
        String saved = encode(board);
        assertTrue(saved.contains("\"version\":" + BoardFile.VERSION));
        assertEquals(tilted, BoardFile.readNative(saved).getHex(0, 0).getDecorations().getFirst());
        assertEquals(30, tilted.duplicate().rotationX());
        assertEquals(-45, tilted.transform(.2, .3, 90, false, 2, tilted.placement()).rotationY());
        board.getHex(0, 0).setDecorations(List.of(prop("old", BoardDecoration.Placement.ground())));
        saved = encode(board);
        assertFalse(saved.contains("rotationX")); assertFalse(saved.contains("rotationY"));
        assertEquals(0, BoardFile.readNative(saved).getHex(0, 0).getDecorations().getFirst().rotationX());
    }
    /** A board stores each model's path and its stable id: one saved before the model moved still finds it. */
    @Test void aModelSavedBeforeItMovedLoadsFromItsNewPathByItsId() throws Exception {
        Board board = Board.createEmptyBoard(1, 1);
        board.getHex(0, 0).setDecorations(List.of(new BoardDecoration("tree", "prop", "vegetation/pine", null, 0, 0, 0,
              false, 1, BoardDecoration.Placement.ground(), 0)));
        String saved = encode(board);
        String uid = BoardModelIds.uid("vegetation/pine");
        assertNotNull(uid);
        assertTrue(saved.contains("\"asset\":\"vegetation/pine\",\"uid\":\"" + uid + "\""), saved);
        // Saved while the pine still lay at the board root: its old path beside its id.
        String old = saved.replace("\"asset\":\"vegetation/pine\"", "\"asset\":\"pine\"");
        assertEquals("vegetation/pine", BoardFile.readNative(old).getHex(0, 0).getDecorations().getFirst().asset());
        assertEquals("pine", BoardFile.readNative(old.replace(uid, "unknown0")).getHex(0, 0).getDecorations().getFirst().asset(),
              "An id no model has keeps the saved path");
    }
    @Test void stretchRoundTripsOnlyWhenSetAndEveryCopyOrTransformKeepsIt() throws Exception {
        Board board = Board.createEmptyBoard(1, 1);
        var stretch = new BoardDecoration.Stretch(.805556, 1, 1.5);
        var roof = prop("roof", BoardDecoration.Placement.ground()).withStretch(stretch).withGroup("g");
        board.getHex(0, 0).setDecorations(List.of(roof, prop("plain", BoardDecoration.Placement.ground())));
        String saved = encode(board);
        assertTrue(saved.contains("\"stretch\":[0.805556,1.0,1.5]"), saved);
        assertEquals(1, saved.split("\"stretch\"", -1).length - 1, "An unstretched object omits the field");
        assertEquals(board.getHex(0, 0).getDecorations(), BoardFile.readNative(saved).getHex(0, 0).getDecorations());
        assertEquals(BoardDecoration.Stretch.NONE, BoardFile.readNative(saved).getHex(0, 0).getDecorations().get(1).stretch());
        for (String invalid : List.of("[0.805556,1.0]", "[0.805556,0,1.5]", "[0.805556,-1,1.5]", "1.5")) {
            assertThrows(java.io.IOException.class, () -> BoardFile.readNative(saved.replace("[0.805556,1.0,1.5]", invalid)), invalid);
        }
        assertEquals(stretch, BoardFile.fromClipboard(BoardFile.clipboard(board.getHex(0, 0))).getDecorations().getFirst().stretch());
        assertEquals(stretch, roof.duplicate().stretch());
        assertEquals(stretch, BoardDecoration.copies(List.of(roof)).getFirst().stretch());
        assertEquals(stretch, roof.transform(.2, .3, 90, true, 2, roof.placement()).stretch());
        assertEquals(stretch, roof.withGroup(null).stretch());
        assertEquals(stretch, roof.flip(true, true).stretch(), "A reflection is the mirror flag; the local axes keep their factors");
        Board combined = megamek.common.util.BoardUtilities.combine(1, 1, 1, 2, new Board[] { board, board },
              megamek.common.loaders.MapSettings.MEDIUM_GROUND);
        var second = combined.getHex(0, 1).getDecorations().getFirst();
        assertNotEquals(roof.id(), second.id(), "The second sheet's copy has a fresh identity");
        assertEquals(stretch, second.stretch(), "Combining sheets keeps it");
    }
    @Test void slotColoursRoundTripOnlyWhenSetAndEveryCopyOrTransformKeepsThem() throws Exception {
        Board board = Board.createEmptyBoard(1, 1);
        var colours = BoardDecoration.Colours.of(null, "#63753D", null, null);
        assertEquals(java.util.Arrays.asList(null, "#63753d"), colours.slots(), "Lower case, trailing defaults dropped");
        var pond = prop("pond", BoardDecoration.Placement.ground()).withColours(colours).withGroup("g");
        board.getHex(0, 0).setDecorations(List.of(pond, prop("plain", BoardDecoration.Placement.ground())));
        String saved = encode(board);
        assertTrue(saved.contains("\"colours\":[null,\"#63753d\"]"), saved);
        assertEquals(1, saved.split("\"colours\"", -1).length - 1, "An object in its model's own colours omits the field");
        assertEquals(board.getHex(0, 0).getDecorations(), BoardFile.readNative(saved).getHex(0, 0).getDecorations());
        for (String invalid : List.of("[null,\"#63753\"]", "[null,\"red\"]", "[1,2]", "\"#63753d\"",
              "[null,null,null,null,\"#63753d\"]")) {
            assertThrows(Exception.class, () -> BoardFile.readNative(saved.replace("[null,\"#63753d\"]", invalid)), invalid);
        }
        assertEquals(colours, BoardFile.fromClipboard(BoardFile.clipboard(board.getHex(0, 0))).getDecorations().getFirst().colours());
        assertEquals(colours, pond.duplicate().colours());
        assertEquals(colours, BoardDecoration.copies(List.of(pond)).getFirst().colours());
        assertEquals(colours, pond.transform(.2, .3, 90, true, 2, pond.placement()).colours());
        assertEquals(colours, pond.withStretch(new BoardDecoration.Stretch(2, 1, 1)).withGroup(null).colours());
        assertEquals(colours, pond.flip(true, true).colours());
        Board combined = megamek.common.util.BoardUtilities.combine(1, 1, 1, 2, new Board[] { board, board },
              megamek.common.loaders.MapSettings.MEDIUM_GROUND);
        assertEquals(colours, combined.getHex(0, 1).getDecorations().getFirst().colours(), "Combining sheets keeps them");
        assertEquals(BoardDecoration.Colours.NONE, colours.with(1, null), "Every slot back to its own colour stores nothing");
        assertThrows(IllegalArgumentException.class, () -> new BoardDecoration("paint", "decal", "decal/car-park/straight-4", null,
              0, 0, 0, false, 1, BoardDecoration.Placement.ground(), 0).withColours(colours), "A decal has one paint colour");
        var letter = new BoardDecoration("paint", "decal", "decal/lettering/letter-a", null, 0, 0, 0, false, 1,
              BoardDecoration.Placement.ground(), 0).withColours(BoardDecoration.Colours.of("#000000"));
        board.getHex(0, 0).setDecorations(List.of(letter));
        assertEquals(List.of(letter), BoardFile.readNative(encode(board)).getHex(0, 0).getDecorations(), "Its paint is kept");
    }

    @TempDir Path directory;

    private static BoardDecoration prop(String id, BoardDecoration.Placement placement) {
        return new BoardDecoration(id, "prop", "scenery/vehicles/car", "Car", -.24, .18, 30,
              false, .8, placement, 0);
    }

    private static String encode(Board board) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BoardFile.write(board, output);
        return output.toString(StandardCharsets.UTF_8);
    }

    /** Water and pavement keep no derived sides: an older file's list is ignored and their sides derive on reading. */
    @Test void waterAndPavementDeriveTheirSidesOnReading() throws Exception {
        Board loaded = BoardFile.readNative("""
              {"format":"megamek-board","version":3,"size":{"width":1,"height":2},
               "hexes":[{"at":[1,1],"elevation":0,"components":{"water":{"depth":1,"connections":["n"]}}},
                        {"at":[1,2],"elevation":0,"components":{"water":{"depth":1}}}]}
              """);
        var water = loaded.getHex(0, 0).getTerrain(Terrains.WATER);
        assertFalse(water.hasExitsSpecified());
        assertTrue(water.exitsTo(loaded.getHex(0, 1).getTerrain(Terrains.WATER)));
        assertTrue(loaded.getHex(0, 0).containsTerrainExit(Terrains.WATER, 3), "Toward the water south of it");
    }

    @Test void nativeRoundTripPreservesIndependentInstancesAndMetadata() throws Exception {
        Board board = Board.createEmptyBoard(2, 1);
        board.setDocumentName("Park \"A\"");
        board.setDescription("First paragraph.\n\nSecond paragraph.");
        board.setSourceHeader("# Original author\r\n# License\r\n");
        board.addTag("example");
        board.setAnnotations(new Coords(0, 0), List.of("Note one", "Note two"));
        board.setAnnotations(new Coords(5, 5), List.of("Keep an old out-of-bounds annotation"));
        Hex hex = new Hex(0, "bridge:1:9;bridge_cf:40;bridge_elev:4;fluff:2001", "CustomTheme");
        hex.setDecorations(List.of(prop("car-a", BoardDecoration.Placement.ground()),
              prop("car-b", BoardDecoration.Placement.absolute(4)),
              new BoardDecoration("decal-a", "decal", "hexes/marking.png", null, 0, 0, 15, true, 1,
                    BoardDecoration.Placement.surface("bridge", "deck", 0), 2)));
        hex.setAppearance(Map.of("building", new HexAppearance(null, "buildings/missing-but-retained", null, null),
              "bridge", HexAppearance.PILLARS));
        board.setHex(0, 0, hex);
        String serialized = encode(board);
        assertEquals(2, serialized.lines().filter(line -> line.contains("\"at\"") && line.contains("\"elevation\"")).count());
        assertTrue(serialized.contains("\"appearance\":{\"variant\":\"bridge/pillars\"}"), serialized);
        Board loaded = BoardFile.readNative(serialized);
        assertEquals(serialized, encode(loaded));
        assertTrue(HexAppearance.pillars(loaded.getHex(0, 0).getAppearance()), "The bridge's Pillars toggle round-trips");
        assertTrue(HexAppearance.pillars(BoardFile.fromClipboard(BoardFile.clipboard(hex)).getAppearance()), "and copies");
        // The other two bridge types round-trip the same way; only the 3D board reads them.
        for (var type : List.of(HexAppearance.BUILT_BRIDGE, HexAppearance.NATURAL_BRIDGE)) {
            Board typed = Board.createEmptyBoard(1, 1);
            Hex bridge = new Hex(0, "bridge:1:9;bridge_cf:40;bridge_elev:1", "");
            bridge.setAppearance(Map.of("bridge", type));
            typed.setHex(0, 0, bridge);
            String text = encode(typed);
            assertTrue(text.contains("\"appearance\":{\"variant\":\"" + type.variant() + "\"}"), text);
            Board back = BoardFile.readNative(text);
            assertEquals(type, back.getHex(0, 0).getAppearance().get("bridge"));
            assertEquals(type == HexAppearance.BUILT_BRIDGE, HexAppearance.bridgeBuilt(back.getHex(0, 0).getAppearance()));
        }
        // A truss stores its colours like a placed object, in .board2 and on the clipboard; only a truss stores them.
        Board truss = Board.createEmptyBoard(1, 1);
        Hex steel = new Hex(0, "bridge:1:9;bridge_cf:40;bridge_elev:1", "");
        var green = HexAppearance.built(true, true, BoardDecoration.Colours.of("#2E5233"));
        steel.setAppearance(Map.of("bridge", green));
        truss.setHex(0, 0, steel);
        String text = encode(truss);
        assertTrue(text.contains("\"appearance\":{\"variant\":\"bridge/truss-pillars\",\"colours\":[\"#2e5233\"]}"), text);
        assertEquals(green, BoardFile.readNative(text).getHex(0, 0).getAppearance().get("bridge"));
        assertEquals(green, BoardFile.fromClipboard(BoardFile.clipboard(steel)).getAppearance().get("bridge"));
        assertTrue(HexAppearance.truss(steel.getAppearance()) && HexAppearance.pillars(steel.getAppearance())
              && Boolean.TRUE.equals(HexAppearance.bridgeBuilt(steel.getAppearance())), "A truss with piers is a built bridge");
        assertThrows(java.io.IOException.class, () -> BoardFile.readNative(text.replace("bridge/truss-pillars", "bridge/pillars")),
              "A standard deck has no colours");
        assertThrows(IllegalArgumentException.class, () -> new HexAppearance("ground/blend/a", null, null, null,
              BoardDecoration.Colours.of("#2e5233")).validateOwner("ground"));
        assertThrows(IllegalArgumentException.class, () -> green.validateOwner("road"));
        // A bridge appearance is a plain variant: an asset or a material belongs to another owner.
        assertThrows(IllegalArgumentException.class, () -> new HexAppearance(null, "buildings/a", null, null).validateOwner("bridge"));
        assertThrows(IllegalArgumentException.class, () -> new HexAppearance(null, null, "asphalt", null).validateOwner("bridge"));
        assertEquals(hex.getDecorations(), loaded.getHex(0, 0).getDecorations());
        assertEquals(board.getAnnotations(), loaded.getAnnotations());
        assertEquals(board.getSourceHeader(), loaded.getSourceHeader());
        assertEquals(2001, loaded.getHex(0, 0).terrainLevel(Terrains.FLUFF), "Server state, never legacy art, is kept");
        assertTrue(loaded.isNativeFormat());
    }

    /** A vegetation look's rotation is stored with it in 30-degree steps; no rotation (0) is not written. */
    @Test void aLooksRotationRoundTripsInItsStepsOnVegetationOnly() throws Exception {
        Board yard = Board.createEmptyBoard(1, 1);
        Hex hex = new Hex(0, "woods:1;foliage_elev:2", "");
        var turned = new HexAppearance("woods/container-yard/quay-crane", null, null, null, BoardDecoration.Colours.NONE, 240);
        hex.setAppearance(Map.of("vegetation", turned));
        yard.setHex(0, 0, hex);
        String text = encode(yard);
        assertTrue(text.contains("\"appearance\":{\"variant\":\"woods/container-yard/quay-crane\",\"rotation\":240}"), text);
        assertEquals(turned, BoardFile.readNative(text).getHex(0, 0).getAppearance().get("vegetation"));
        assertEquals(turned, BoardFile.fromClipboard(BoardFile.clipboard(hex)).getAppearance().get("vegetation"));
        var straight = new HexAppearance(turned.variant(), null, null, null, BoardDecoration.Colours.NONE, 0);
        assertEquals(new HexAppearance(turned.variant(), null, null, null), straight, "0 is no rotation");
        hex.setAppearance(Map.of("vegetation", straight));
        assertFalse(encode(yard).contains("rotation"));
        assertNull(BoardFile.readNative(text.replace("240", "0")).getHex(0, 0).getAppearance().get("vegetation").rotation());
        for (String refused : new String[] { "45", "360", "-30", "60.5" }) {
            assertThrows(java.io.IOException.class, () -> BoardFile.readNative(text.replace("240", refused)), refused);
        }
        assertThrows(java.io.IOException.class, () -> BoardFile.readNative(text.replace("\"vegetation\":", "\"rough\":")),
              "Only a vegetation look turns");
        assertThrows(IllegalArgumentException.class, () -> new HexAppearance(null, null, "asphalt", null,
              BoardDecoration.Colours.NONE, 60).validateOwner("road"),
              "A rotation turns a design");
    }

    @Test void importKeepsLegacySelectorsAndNeverOverwritesTheOldFile() throws Exception {
        String legacy = "# Attribution\nsize 1 1\noption exit_roads_to_pavement false\n"
              + "description \"Old description\"\ntag \"Old tag\"\nnote 0101 \"Legacy note\"\n"
              + "hex 0101 0 \"building:1:65;bldg_elev:1;bldg_cf:15\" \"lunar\"\nend\n";
        Path original = directory.resolve("old.board"); Files.writeString(original, legacy);
        Board imported = BoardFile.read(original);
        assertEquals(65, imported.getHex(0, 0).getTerrain(Terrains.BUILDING).getExits());
        assertEquals(List.of("Legacy note"), imported.getAnnotations(new Coords(1, 1)));
        assertThrows(java.io.IOException.class, () -> BoardFile.save(imported, original));
        Path destination = directory.resolve("new.board2"); BoardFile.save(imported, destination);
        assertEquals(legacy, Files.readString(original));
        assertEquals(encode(imported), encode(BoardFile.read(destination)));
        try (var files = Files.list(directory)) { assertFalse(files.anyMatch(p -> p.getFileName().toString().endsWith(".tmp"))); }
    }

    @Test void malformedDocumentsCannotFallThroughToLegacyOrReplaceAnExistingBoard() {
        Board existing = Board.createEmptyBoard(3, 2);
        for (String invalid : List.of(
              "{\"format\":\"megamek-board\",\"version\":3,\"size\":{\"width\":1,\"height\":1},\"hexes\":[]}",
              "{\"format\":\"megamek-board\",\"version\":3,\"version\":3}",
              "{broken json")) {
            assertThrows(IllegalArgumentException.class, () -> existing.load(invalid, null));
            assertEquals(3, existing.getWidth()); assertEquals(2, existing.getHeight());
        }
    }

    @Test void rejectDuplicateCoordinatesObjectIdsAndInvalidPlacement() throws Exception {
        Board board = Board.createEmptyBoard(2, 1);
        board.getHex(0, 0).setDecorations(List.of(prop("same", BoardDecoration.Placement.ground())));
        board.getHex(1, 0).setDecorations(List.of(prop("different", BoardDecoration.Placement.ground())));
        String valid = encode(board);
        assertThrows(java.io.IOException.class, () -> BoardFile.readNative(valid.replace("different", "same")));
        assertThrows(java.io.IOException.class, () -> BoardFile.readNative(valid.replace("[2,1]", "[1,1]")));
        assertThrows(java.io.IOException.class, () -> BoardFile.readNative(valid.replace("\"scale\":0.8", "\"scale\":0")));
        assertThrows(java.io.IOException.class, () -> BoardFile.readNative(valid.replace("\"prop\"", "\"decal\"")
              .replace("\"offset\":0.0", "\"offset\":2.0")));
        assertThrows(java.io.IOException.class, () -> BoardFile.readNative(valid.replace("\"top\"", "\"future-surface\"")));
        assertThrows(java.io.IOException.class, () -> BoardFile.readNative(valid.replace("\"mode\":\"surface\"", "\"mode\":\"surface\",\"level\":4")));
    }

    @Test void copyClipboardAndJavaSerializationPreserveVisualsWithCorrectIdentity() throws Exception {
        Hex original = new Hex();
        original.setAppearance(Map.of("road", new HexAppearance("road/rounded", null, "roads/gravel", null)));
        original.setDecorations(List.of(prop("original", BoardDecoration.Placement.absolute(5))));
        Hex undo = original.duplicate();
        assertEquals(original.getDecorations(), undo.getDecorations());
        Hex pasted = Hex.parseClipboardString(original.getClipboardString());
        assertNotNull(pasted);
        assertNotEquals(original.getDecorations().getFirst().id(), pasted.getDecorations().getFirst().id());
        assertEquals(original.getAppearance(), pasted.getAppearance());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(original); }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            Hex loaded = (Hex) input.readObject();
            assertEquals(original.getDecorations(), loaded.getDecorations());
            assertEquals(original.getAppearance(), loaded.getAppearance());
        }
        original.setDecorations(List.of());
        assertEquals(1, undo.getDecorations().size());
    }

    @Test void javaSerializationKeepsAnExplicitGantryOverride() throws Exception {
        Hex original = new Hex(0, "road:1:11", "");
        var explicit = new HexAppearance(null, null, null, null, BoardDecoration.Colours.NONE,
              null, null, null, HexAppearance.TrafficControl.GANTRY);
        original.setAppearance(Map.of("road", explicit));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(original); }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            Hex loaded = (Hex) input.readObject();
            assertEquals(original.getAppearance(), loaded.getAppearance());
            assertEquals(HexAppearance.TrafficControl.GANTRY, HexAppearance.trafficControl(loaded.getAppearance(), "road", 11));
            assertEquals(explicit, HexAppearance.withTrafficControl(loaded.getAppearance().get("road"), HexAppearance.TrafficControl.GANTRY));
        }
    }

    @Test void nativeMetadataAndLobbyNamesRemainDiscoverable() throws Exception {
        Board board = Board.createEmptyBoard(2, 3); board.addTag("test tag");
        Path file = directory.resolve("native.board2"); BoardFile.save(board, file);
        assertEquals(new BoardDimensions(2, 3), BoardFile.metadata(file).size());
        assertEquals(java.util.Set.of("test tag"), BoardFile.metadata(file).tags());
        assertEquals(new BoardDimensions(2, 3), Board.getSize(file.toFile()));
        assertEquals("native.board2", BoardFile.fileName(BoardFile.selectionName("native.board2")));
        assertEquals("legacy.board", BoardFile.fileName(BoardFile.selectionName("legacy.board")));
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var original = json.readTree(Files.readString(file));
        var reordered = json.createObjectNode();
        reordered.set("hexes", original.get("hexes"));
        original.fields().forEachRemaining(entry -> { if (!entry.getKey().equals("hexes")) { reordered.set(entry.getKey(), entry.getValue()); } });
        Files.writeString(file, reordered.toString());
        assertEquals(new BoardDimensions(2, 3), BoardFile.metadata(file).size(), "Indexing must accept metadata after the hex array");
    }

    @Test void failedSaveKeepsThePreviousFile() throws Exception {
        Board board = Board.createEmptyBoard(2, 1);
        Path file = directory.resolve("safe.board2"); BoardFile.save(board, file);
        String original = Files.readString(file);
        var car = new BoardDecoration("car", "prop", "car", null, 0, 0, 0, false, 1, BoardDecoration.Placement.ground(), 0);
        board.getHex(0, 0).setDecorations(List.of(car)); board.getHex(1, 0).setDecorations(List.of(car));
        assertThrows(java.io.IOException.class, () -> BoardFile.save(board, file), "Two objects with one ID");
        assertEquals(original, Files.readString(file));
    }

    /**
     * A board that breaks the game's rules is written (decision P: the editor saves it as the old editor did); the one
     * reader refuses it unless it continues on errors, which only the editor does.
     */
    @Test void rulesErrorsAreWrittenAndOnlyALenientReadOpensThem() throws Exception {
        Board board = Board.createEmptyBoard(1, 1);
        board.getHex(0, 0).addTerrain(new megamek.common.units.Terrain(Terrains.BUILDING, 1));
        assertFalse(board.isValid());
        Path file = directory.resolve("rules.board2"); BoardFile.save(board, file);
        assertThrows(java.io.IOException.class, () -> BoardFile.read(file));
        assertThrows(java.io.IOException.class, () -> BoardFile.readNative(Files.readString(file)));
        Board lenient = BoardFile.read(file, true);
        assertFalse(lenient.isValid());
        assertThrows(java.io.IOException.class, () -> BoardFile.read(directory.resolve("missing.board2"), true));
        assertThrows(java.io.IOException.class, () -> BoardFile.readNative("{\"format\":\"megamek-board\",\"version\":3}", true),
              "A malformed document is refused either way");
    }

    @Test void shippedCompositionExampleLoadsWithIndependentHeightLayers() throws Exception {
        Board example = BoardFile.read(megamek.common.Configuration.boardsDir().toPath()
              .resolve("examples/6x6 Native Editor Composition.board2"));
        assertEquals(4, example.getHex(3, 1).getDecorations().size());
        var bridge = example.getHex(1, 1);
        assertEquals(4, bridge.terrainLevel(Terrains.BRIDGE_ELEV));
        assertTrue(bridge.getDecorations().stream().anyMatch(d -> d.placement().receiver().terrain().equals("ground")));
        assertTrue(bridge.getDecorations().stream().anyMatch(d -> d.placement().receiver().terrain().equals("bridge")));
        var elevatedHex = example.getHex(1, 3);
        var elevated = elevatedHex.getDecorations();
        // The train follows its deck receiver; only the independent beacon stores an absolute height.
        assertEquals(java.util.Set.of(4.0, 5.0), elevated.stream().map(d -> d.placement().level(elevatedHex))
              .collect(java.util.stream.Collectors.toSet()));
        // The route marker keeps its N/S sides; a board flip reflects them as terrain exits are.
        var route = RailRoute.find(example.getHex(1, 3), RailRoute.Kind.MAGLEV);
        assertEquals(9, route.connections());
        assertEquals(9, route.flip(true, false).connections());
        assertEquals(2 | 16, route.withConnections(4 | 32).flip(true, false).connections());
        assertEquals(4 | 32, route.withConnections(2 | 16).flip(false, true).connections());
        var paint = example.getHex(4, 4).getDecorations().getFirst();
        assertEquals(2.2, paint.scale()); assertFalse(paint.clipToHex());
        assertEquals(encode(example), encode(BoardFile.readNative(encode(example))));
    }

    @Test void omittedClippingUsesItsDefaultAndScaledSpanningPaintRoundTrips() throws Exception {
        Board board = Board.createEmptyBoard(2, 2);
        BoardDecoration decal = new BoardDecoration("paint", "decal", "decal/damage/rubble-light-path", null,
              1.2, -.4, 35, true, 3, BoardDecoration.Placement.ground(), 2, false);
        board.getHex(0, 0).setDecorations(List.of(decal));
        String text = encode(board);
        assertTrue(text.contains("\"version\":" + BoardFile.VERSION));
        assertEquals(decal, BoardFile.readNative(text).getHex(0, 0).getDecorations().getFirst());
        assertTrue(BoardFile.readNative(text.replace(",\"clipToHex\":false", ""))
              .getHex(0, 0).getDecorations().getFirst().clipToHex(), "Earlier development maps retain owner clipping");
        assertFalse(BoardFile.fromClipboard(BoardFile.clipboard(board.getHex(0, 0))).getDecorations().getFirst().clipToHex());
        assertEquals(decal.clipToHex(), decal.duplicate().clipToHex());
    }

    @Test void groupsRoundTripAndCopiesFormFreshGroups() throws Exception {
        Board board = Board.createEmptyBoard(2, 1);
        var table = prop("table", BoardDecoration.Placement.ground()).withGroup("L1_1_g0");
        var bench = prop("bench", BoardDecoration.Placement.ground()).withGroup("L1_1_g0");
        var loose = prop("loose", BoardDecoration.Placement.ground());
        board.getHex(0, 0).setDecorations(List.of(table, bench, loose));
        board.getHex(1, 0).setDecorations(List.of(prop("other", BoardDecoration.Placement.ground()).withGroup("L1_1_g0")));
        String saved = encode(board);
        assertTrue(saved.contains("\"group\":\"L1_1_g0\""));
        Board loaded = BoardFile.readNative(saved);
        assertEquals(board.getHex(0, 0).getDecorations(), loaded.getHex(0, 0).getDecorations());
        assertEquals("L1_1_g0", loaded.getHex(1, 0).getDecorations().getFirst().group(), "A group may span hexes");
        assertThrows(java.io.IOException.class, () -> BoardFile.readNative(saved.replace("\"L1_1_g0\"", "\" \"")));
        Hex ungrouped = board.getHex(0, 0).duplicate();
        ungrouped.setDecorations(List.of(table.withGroup(null), bench, loose));
        assertNotEquals(BoardFile.hexNode(board.getHex(0, 0)), BoardFile.hexNode(ungrouped),
              "The editor records an edit only when the serialized hex changes");

        assertNull(table.duplicate().group());
        assertEquals("L1_1_g0", table.transform(.1, .1, 90, true, 2, table.placement()).group());
        List<BoardDecoration> copies = BoardDecoration.copies(List.of(table, bench, loose));
        assertNotEquals("L1_1_g0", copies.get(0).group());
        assertEquals(copies.get(0).group(), copies.get(1).group());
        assertNull(copies.get(2).group());
        assertTrue(copies.stream().noneMatch(copy -> List.of("table", "bench", "loose").contains(copy.id())));
        var pasted = BoardFile.fromClipboard(BoardFile.clipboard(board.getHex(0, 0))).getDecorations();
        assertNotEquals("L1_1_g0", pasted.get(0).group());
        assertEquals(pasted.get(0).group(), pasted.get(1).group());
        assertNull(pasted.get(2).group());
    }
}
